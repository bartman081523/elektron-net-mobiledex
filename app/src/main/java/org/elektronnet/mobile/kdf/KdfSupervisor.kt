// SPDX-License-Identifier: GPL-3.0-only
// This file is part of the Elektron DEX line, see LICENSE-GPL-3.0 and doc-elektron/concept-elektron-dex.md.

package org.elektronnet.mobile.kdf

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.elektronnet.mobile.ChainConfig
import org.elektronnet.mobile.node.Json
import org.elektronnet.mobile.node.OutputBuffer

sealed interface KdfState {
    data object Stopped : KdfState
    data object Starting : KdfState
    data object Running : KdfState
    data class Error(val message: String) : KdfState
}

class KdfSupervisor(
    private val context: Context,
    val rpcPort: Int = KdfConfig.DEFAULT_RPC_PORT,
    val seedPhraseProvider: () -> String? = { null },
    val output: OutputBuffer = OutputBuffer(500),
) {
    val kdfDir = File(context.filesDir, "kdf").apply { mkdirs() }
    private val confFile = File(kdfDir, "MM2.json")
    private val secretsFile = File(kdfDir, "secrets.json")
    private val pidFile = File(kdfDir, "kdf.pid")
    private val tmpDir = File(kdfDir, "tmp").apply { mkdirs() }
    private val logFile = File(kdfDir, "kdf.log")

    private val lock = Any()
    private var process: Process? = null

    @Volatile
    var rpcPassword: String = ""
        private set

    @Volatile
    private var kdfPassphrase: String = ""

    val client: KdfClient = KdfClient(
        rpcHost = KdfConfig.DEFAULT_RPC_HOST,
        rpcPort = rpcPort,
        rpcPasswordProvider = { rpcPassword },
    )

    val pruner: SwapPruner = SwapPruner(kdfDir)

    fun getRecentSwapsMerged(limit: Int = 10): List<SwapStatus> {
        val live = try { client.myRecentSwaps(limit) } catch (_: Exception) { emptyList() }
        val pruned = pruner.getPrunedReceipts().map { it.toSwapStatus() }
        val prunedUuids = pruned.map { it.uuid }.toSet()
        val nonPrunedLive = live.filter { it.uuid !in prunedUuids }
        return (nonPrunedLive + pruned).sortedByDescending { it.events.lastOrNull()?.timestamp ?: 0L }
    }

    var state: KdfState by mutableStateOf(KdfState.Stopped)
        private set

    var versionInfo: String by mutableStateOf("")
        private set

    init {
        loadOrGenerateSecrets()
    }

    private fun loadOrGenerateSecrets() {
        if (secretsFile.isFile) {
            try {
                val parsed = Json.parse(secretsFile.readText()) as? Map<*, *>
                val pw = parsed?.get("rpc_password")?.toString().orEmpty()
                val phrase = parsed?.get("kdf_passphrase")?.toString().orEmpty()
                if (KdfConfig.isPasswordPolicyOk(pw) && phrase.length >= 32) {
                    rpcPassword = pw
                    kdfPassphrase = phrase
                    return
                }
            } catch (e: Exception) {
                // fall through to generate
            }
        }
        rpcPassword = KdfConfig.generateRpcPassword()
        kdfPassphrase = KdfConfig.generateKdfPassphrase()
        persistSecrets()
    }

    private fun persistSecrets() {
        val data = mapOf("rpc_password" to rpcPassword, "kdf_passphrase" to kdfPassphrase)
        secretsFile.writeText(Json.stringify(data))
    }

    private data class NetworkProfile(
        val seeds: List<String> = emptyList(),
        val electrum: List<String> = emptyList(),
        val relayIp: String? = null,
    )

    /**
     * Reads kdf/net.txt. Unknown keys are ignored on purpose, so a future
     * option does not stop an older build from starting.
     */
    private fun readNetworkProfile(): NetworkProfile {
        val file = File(kdfDir, "net.txt")
        if (!file.isFile) return NetworkProfile()
        val seeds = mutableListOf<String>()
        val electrum = mutableListOf<String>()
        var relay: String? = null
        for (raw in file.readLines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val key = line.substringBefore('=', "").trim().lowercase()
            val value = line.substringAfter('=', "").trim()
            if (value.isEmpty()) continue
            when (key) {
                "seed", "seednode", "peer" -> seeds += value
                "electrum", "electrum_server" -> electrum += value
                "relay", "relay_ip" -> relay = value
            }
        }
        return NetworkProfile(seeds = seeds, electrum = electrum, relayIp = relay)
    }

    fun binary(): File = File(context.applicationInfo.nativeLibraryDir, "libkdfbin.so")

    fun isBinaryAvailable(): Boolean {
        val bin = binary()
        return bin.exists() && bin.canExecute()
    }

    fun start() {
        synchronized(lock) {
            if (state == KdfState.Starting || state == KdfState.Running) return
            state = KdfState.Starting
            versionInfo = ""
            Thread({ runSupervisor() }, "kdf-supervisor").apply { isDaemon = true }.start()
        }
    }

    fun stop(timeoutMs: Long = 5_000) {
        val target: Process
        synchronized(lock) {
            target = process ?: run {
                state = KdfState.Stopped
                return
            }
            process = null
            state = KdfState.Stopped
        }
        target.destroy()
        if (!target.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            target.destroyForcibly()
            target.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        }
        if (pidFile.exists()) pidFile.delete()
    }

    private fun runSupervisor() {
        try {
            killStale()
            val bin = binary()
            if (!bin.exists() || !bin.canExecute()) {
                val err = "libkdfbin.so missing or not executable at ${bin.absolutePath}"
                output.add(err)
                state = KdfState.Error(err)
                return
            }

            // Derive from the wallet seed when the app provides one, otherwise
            // use this install's own persisted passphrase. A shared constant
            // would give every phone the same swap wallet.
            val seed = seedPhraseProvider()
            val passphrase = if (!seed.isNullOrBlank()) {
                KdfConfig.deriveKdfPassphrase(seed)
            } else {
                kdfPassphrase
            }

            // net.txt is the network profile for this install, one key per
            // line: seed=<ip|url> (repeatable), electrum=<host:port>
            // (repeatable), relay=<ip> to serve as a P2P relay ourselves.
            // peers.txt (bare seed lines) stays supported for older setups.
            val net = readNetworkProfile()
            val extraPeers = if (net.seeds.isNotEmpty()) {
                net.seeds
            } else {
                File(kdfDir, "peers.txt")
                    .takeIf { it.isFile }
                    ?.readLines()
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() && !it.startsWith("#") }
                    .orEmpty()
            }
            val conf = KdfConfig.buildConfig(
                kdfDir = kdfDir,
                isTestnet = ChainConfig.isTestnet,
                passphrase = passphrase,
                rpcPassword = rpcPassword,
                rpcPort = rpcPort,
                extraSeednodes = extraPeers,
                electrumServers = net.electrum.ifEmpty { KdfConfig.TELEK_ELECTRUM_SERVERS },
                relayIp = net.relayIp,
            )
            confFile.writeText(Json.stringify(conf))

            tmpDir.mkdirs()
            val builder = ProcessBuilder(listOf(bin.absolutePath, confFile.absolutePath))
                .directory(kdfDir)
                .redirectErrorStream(true)
            builder.environment()["TMPDIR"] = tmpDir.absolutePath

            val started = builder.start()
            synchronized(lock) {
                process = started
            }

            Thread({ drainOutput(started) }, "kdf-output").apply { isDaemon = true }.start()
            Thread({ awaitExit(started) }, "kdf-exit").apply { isDaemon = true }.start()
            awaitReady(started)
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            output.add("KDF start error: $msg")
            state = KdfState.Error(msg)
        }
    }

    private fun drainOutput(proc: Process) {
        try {
            proc.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    output.add(line)
                }
            }
        } catch (e: IOException) {
            // Stream closed
        }
    }

    private fun awaitExit(started: Process) {
        val code = started.waitFor()
        synchronized(lock) {
            if (process !== started) return
            process = null
            if (state != KdfState.Stopped) {
                val tail = output.tail(4).joinToString("\n")
                state = KdfState.Error("KDF exited code $code: $tail")
            }
        }
    }

    private fun awaitReady(started: Process) {
        val deadline = System.currentTimeMillis() + 60_000
        while (started.isAlive && System.currentTimeMillis() < deadline) {
            try {
                val v = client.version()
                if (v.isNotBlank()) {
                    versionInfo = v
                    activateDefaultCoins()
                    synchronized(lock) {
                        if (process === started) {
                            state = KdfState.Running
                        }
                    }
                    return
                }
            } catch (e: Exception) {
                // Not ready yet
            }
            Thread.sleep(500)
        }
        synchronized(lock) {
            if (process === started && state == KdfState.Starting) {
                state = KdfState.Error("KDF timeout waiting for RPC")
            }
        }
    }

    private fun activateDefaultCoins() {
        if (ChainConfig.isTestnet) {
            // Use the profile's indexer, not a hardcoded loopback: on a LAN
            // install the electrs server is another host.
            val electrumServers = readNetworkProfile().electrum.ifEmpty { KdfConfig.TELEK_ELECTRUM_SERVERS }
            try {
                client.enableElectrum("tELEK", electrumServers)
                output.add("tELEK activated on KDF daemon")
            } catch (e: Exception) {
                output.add("Warning: tELEK activation failed: ${e.message}")
            }
            try {
                client.enableElectrum("tBTC", electrumServers)
                output.add("tBTC activated on KDF daemon")
            } catch (e: Exception) {
                output.add("Warning: tBTC activation failed: ${e.message}")
            }
            try {
                client.enableEth(
                    coin = "SEPOLIAETH",
                    urls = listOf("https://ethereum-sepolia-rpc.publicnode.com", "https://sepolia.drpc.org"),
                    swapContract = "0xeA6D65434A15377081495a9E7C5893543E7c32cB",
                )
                output.add("SEPOLIAETH activated on KDF daemon")
            } catch (e: Exception) {
                output.add("Warning: SEPOLIAETH activation failed: ${e.message}")
            }
        }
    }

    private fun killStale() {
        if (!pidFile.isFile) return
        val pid = pidFile.readText().trim().toIntOrNull() ?: return
        try {
            android.os.Process.killProcess(pid)
        } catch (e: Exception) {
        }
        pidFile.delete()
    }
}