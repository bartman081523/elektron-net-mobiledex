package org.elektronnet.mobile.kdf

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class KdfConfigTest {

    private fun configuredTickers(isTestnet: Boolean): List<String> {
        val conf = KdfConfig.buildConfig(
            kdfDir = File(System.getProperty("java.io.tmpdir"), "kdf-config-test"),
            isTestnet = isTestnet,
            passphrase = "test-passphrase-0123456789abcdef",
            rpcPassword = "K_0123456789abcdef0123456789abcdef",
        )
        return (conf["coins"] as List<*>).map { (it as Map<*, *>)["coin"].toString() }
    }

    @Test
    fun testnetActivationListMatchesConfiguration() {
        assertEquals(
            configuredTickers(true),
            KdfConfig.coinsFor(true, emptyList()).map { it.ticker },
        )
    }

    @Test
    fun mainnetActivationListMatchesConfiguration() {
        assertEquals(
            configuredTickers(false),
            KdfConfig.coinsFor(false, emptyList()).map { it.ticker },
        )
    }

    @Test
    fun electrumProfileReachesEveryUtxoCoin() {
        val servers = listOf("192.168.178.21:50005")
        val utxo = KdfConfig.coinsFor(true, servers).filter { it.kind == KdfConfig.KdfCoinKind.UTXO }
        assertEquals(2, utxo.size)
        assert(utxo.all { it.electrum == servers })
    }

    @Test
    fun ethereumCoinsCarryRpcEndpointsAndSwapContract() {
        val eth = KdfConfig.coinsFor(true, emptyList()).first { it.kind == KdfConfig.KdfCoinKind.ETH }
        assertEquals("SEPOLIAETH", eth.ticker)
        assert(eth.rpcUrls.isNotEmpty())
        assertEquals(KdfConfig.SEPOLIA_SWAP_CONTRACT, eth.swapContract)
        assertEquals(18, eth.decimals)
    }
}
