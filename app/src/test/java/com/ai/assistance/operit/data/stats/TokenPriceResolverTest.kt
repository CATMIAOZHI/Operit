package com.ai.assistance.operit.data.stats

import com.ai.assistance.operit.data.collects.ModelPricingDefaults
import com.ai.assistance.operit.data.collects.PricingCurrency
import com.ai.assistance.operit.data.model.BillingMode
import com.ai.assistance.operit.data.model.TokenStatPriceOverrideEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenPriceResolverTest {

    private val knownDefaults =
        ModelPricingDefaults(
            billingMode = BillingMode.TOKEN,
            inputPricePerMillion = 1.0,
            outputPricePerMillion = 2.0,
            cachedInputPricePerMillion = 0.5,
            pricePerRequest = 0.01,
            currency = PricingCurrency.USD,
        )

    private val zeroDefaults =
        ModelPricingDefaults(
            billingMode = BillingMode.TOKEN,
            inputPricePerMillion = 0.0,
            outputPricePerMillion = 0.0,
            cachedInputPricePerMillion = 0.0,
            pricePerRequest = 0.01,
            currency = PricingCurrency.CNY,
        )

    private fun overrideRow(
        scope: String,
        provider: String,
        model: String,
        configId: String? = null,
        billingMode: BillingMode = BillingMode.TOKEN,
        currency: String = PricingCurrency.USD.name,
        input: Double? = 3.0,
        cached: Double? = 1.5,
        cacheWrite: Double? = 0.75,
        output: Double? = 6.0,
        perRequest: Double? = null,
    ) = TokenPriceResolver.normalizedOverride(
        scope = scope,
        provider = provider,
        model = model,
        configId = configId,
        billingMode = billingMode,
        pricingCurrency = currency,
        inputPricePerMillion = input,
        cachedInputPricePerMillion = cached,
        cacheWritePricePerMillion = cacheWrite,
        outputPricePerMillion = output,
        pricePerRequest = perRequest,
    )

    private fun resolve(
        provider: String = "DEEPSEEK",
        model: String = "deepseek-chat",
        configId: String? = null,
        overrides: List<TokenStatPriceOverrideEntity> = emptyList(),
        legacy: LegacyPriceSettings? = null,
        defaults: ModelPricingDefaults = knownDefaults,
    ) =
        TokenPriceResolver.resolve(
            provider = provider,
            model = model,
            configId = configId,
            overrides = overrides,
            legacyOverride = legacy,
            defaults = defaults,
        )

    @Test
    fun `config override beats provider model override and defaults`() {
        val config = overrideRow("CONFIG", "DEEPSEEK", "deepseek-chat", configId = "cfg-1")
        val providerModel = overrideRow("PROVIDER_MODEL", "DEEPSEEK", "deepseek-chat")

        val resolved = resolve(configId = "cfg-1", overrides = listOf(config, providerModel))

        assertEquals(PricingSource.CONFIG_OVERRIDE, resolved.source)
        assertEquals(3.0, resolved.inputPricePerMillion!!, 1e-9)
        assertTrue(resolved.known)
    }

    @Test
    fun `config override only applies to its own config instance`() {
        val config = overrideRow("CONFIG", "DEEPSEEK", "deepseek-chat", configId = "cfg-1")
        val providerModel = overrideRow("PROVIDER_MODEL", "DEEPSEEK", "deepseek-chat")

        val resolved = resolve(configId = "cfg-other", overrides = listOf(config, providerModel))

        assertEquals(PricingSource.PROVIDER_MODEL_OVERRIDE, resolved.source)
        assertEquals(3.0, resolved.inputPricePerMillion!!, 1e-9)
    }

    @Test
    fun `provider model override beats built-in defaults`() {
        val providerModel = overrideRow("PROVIDER_MODEL", "DEEPSEEK", "deepseek-chat")

        val resolved = resolve(overrides = listOf(providerModel))

        assertEquals(PricingSource.PROVIDER_MODEL_OVERRIDE, resolved.source)
        assertEquals(3.0, resolved.inputPricePerMillion!!, 1e-9)
        assertTrue(resolved.known)
    }

    @Test
    fun `blank cached override inherits lower layer`() {
        val providerModel =
            overrideRow(
                "PROVIDER_MODEL",
                "DEEPSEEK",
                "deepseek-chat",
                input = 3.0,
                cached = null,
                output = 6.0,
            )

        val resolved = resolve(overrides = listOf(providerModel))

        assertEquals(0.5, resolved.cachedInputPricePerMillion!!, 1e-9)
    }

    @Test
    fun `legacy user price is used when no db override exists`() {
        val legacy =
            LegacyPriceSettings(
                inputPricePerMillion = 4.0,
                cachedInputPricePerMillion = 2.0,
                outputPricePerMillion = 8.0,
            )

        val resolved = resolve(legacy = legacy)

        assertEquals(PricingSource.LEGACY_OVERRIDE, resolved.source)
        assertEquals(4.0, resolved.inputPricePerMillion!!, 1e-9)
        assertEquals(8.0, resolved.outputPricePerMillion!!, 1e-9)
        assertTrue(resolved.known)
    }

    @Test
    fun `legacy zero or absent values fall back to defaults and stay known`() {
        val legacy =
            LegacyPriceSettings(
                inputPricePerMillion = 4.0,
                cachedInputPricePerMillion = 0.0,
                outputPricePerMillion = null,
            )

        val resolved = resolve(legacy = legacy)

        assertEquals(4.0, resolved.inputPricePerMillion!!, 1e-9)
        assertEquals(0.5, resolved.cachedInputPricePerMillion!!, 1e-9)
        assertEquals(2.0, resolved.outputPricePerMillion!!, 1e-9)
        assertTrue(resolved.known)
    }

    @Test
    fun `legacy with no user setting falls through to defaults`() {
        val resolved = resolve(legacy = LegacyPriceSettings())

        assertEquals(PricingSource.DEFAULT, resolved.source)
        assertEquals(1.0, resolved.inputPricePerMillion!!, 1e-9)
        assertTrue(resolved.known)
    }

    @Test
    fun `known built-in defaults resolve as DEFAULT source`() {
        val resolved = resolve(defaults = knownDefaults)

        assertEquals(PricingSource.DEFAULT, resolved.source)
        assertTrue(resolved.known)
    }

    @Test
    fun `unknown model falls back to zero pricing marked unknown`() {
        val resolved = resolve(defaults = zeroDefaults)

        assertEquals(PricingSource.UNKNOWN, resolved.source)
        assertFalse(resolved.known)
    }

    @Test
    fun `unknown pricing currency still follows provider convention`() {
        val resolved = resolve(defaults = zeroDefaults)

        assertEquals(PricingCurrency.CNY, resolved.currency)
        assertEquals(BillingMode.TOKEN, resolved.billingMode)
    }

    @Test
    fun `normalized override rows carry normalized business columns`() {
        val rowA = TokenPriceResolver.normalizedOverride("PROVIDER_MODEL", "DEEPSEEK", "deepseek-chat", null, BillingMode.TOKEN, PricingCurrency.USD.name)
        val rowB = TokenPriceResolver.normalizedOverride("PROVIDER_MODEL", "deepseek", "DeepSeek-Chat", null, BillingMode.TOKEN, PricingCurrency.USD.name)
        val rowConfig = TokenPriceResolver.normalizedOverride("CONFIG", "DEEPSEEK", "deepseek-chat", " cfg-1 ", BillingMode.TOKEN, PricingCurrency.USD.name)

        // 规范化后业务列一致（provider/model 小写、configId trim）
        assertEquals(rowA.provider, rowB.provider)
        assertEquals(rowA.model, rowB.model)
        assertEquals("deepseek", rowA.provider)
        assertEquals("deepseek-chat", rowA.model)
        assertEquals("", rowA.configId) // PROVIDER_MODEL 范围用空串
        assertEquals("cfg-1", rowConfig.configId)
        assertTrue(rowA.configId != rowConfig.configId)
    }

    @Test
    fun `resolver matches override rows by normalized business columns`() {
        // 行以原始大小写写入，但业务列规范化；查询侧规范化后仍命中
        val row =
            TokenStatPriceOverrideEntity(
                scope = "PROVIDER_MODEL",
                provider = "DEEPSEEK",
                model = "deepseek-chat",
                configId = "",
                billingMode = BillingMode.TOKEN.name,
                pricingCurrency = PricingCurrency.USD.name,
                inputPricePerMillion = 3.0,
            )

        val resolved = resolve(provider = "deepseek", model = "DeepSeek-Chat", overrides = listOf(row))

        assertEquals(PricingSource.PROVIDER_MODEL_OVERRIDE, resolved.source)
        assertEquals(3.0, resolved.inputPricePerMillion!!, 1e-9)
    }

    @Test
    fun `row business columns and query mismatch cannot resolve wrongly`() {
        // 行内容（业务列）是 openai/gpt-4o：无论怎样“伪造”都不能被 deepseek 查询命中
        val row =
            TokenStatPriceOverrideEntity(
                scope = "PROVIDER_MODEL",
                provider = "openai",
                model = "gpt-4o",
                configId = "",
                billingMode = BillingMode.TOKEN.name,
                pricingCurrency = PricingCurrency.USD.name,
                inputPricePerMillion = 3.0,
            )

        val resolved = resolve(provider = "DEEPSEEK", model = "deepseek-chat", overrides = listOf(row))

        // 不命中伪造行 → 落到内置默认价（而不是错误使用 openai 的价格）
        assertEquals(PricingSource.DEFAULT, resolved.source)
        assertEquals(1.0, resolved.inputPricePerMillion!!, 1e-9)
    }

    @Test
    fun `config and provider model rows with same normalized model stay distinct`() {
        val config = overrideRow("CONFIG", "DEEPSEEK", "deepseek-chat", configId = "cfg-1")
        val providerModel = overrideRow("PROVIDER_MODEL", "DEEPSEEK", "deepseek-chat")

        assertTrue(config.configId != providerModel.configId)
        assertEquals(
            PricingSource.CONFIG_OVERRIDE,
            resolve(configId = "cfg-1", overrides = listOf(config, providerModel)).source
        )
    }

    @Test
    fun `override cache write price is preserved when present`() {
        val providerModel =
            overrideRow(
                "PROVIDER_MODEL",
                "DEEPSEEK",
                "deepseek-chat",
                cacheWrite = 0.75,
            )

        val resolved = resolve(overrides = listOf(providerModel))

        assertEquals(0.75, resolved.cacheWritePricePerMillion!!, 1e-9)
    }

    @Test
    fun `built-in defaults and legacy pricing have no cache write price`() {
        assertNull(resolve(defaults = knownDefaults).cacheWritePricePerMillion)
        assertNull(
            resolve(
                legacy =
                    LegacyPriceSettings(
                        inputPricePerMillion = 4.0,
                        cachedInputPricePerMillion = 2.0,
                        outputPricePerMillion = 8.0,
                    )
            ).cacheWritePricePerMillion
        )
    }

    @Test
    fun `count mode override is known only with per request price`() {
        val withPrice =
            overrideRow(
                "PROVIDER_MODEL",
                "DEEPSEEK",
                "deepseek-chat",
                billingMode = BillingMode.COUNT,
                input = null,
                cached = null,
                output = null,
                perRequest = 0.02,
            )
        // 同一规范化业务键、无按次价格 → 解析结果确定：known = false
        val withoutPrice = withPrice.copy(pricePerRequest = null)

        assertTrue(resolve(overrides = listOf(withPrice)).known)
        assertFalse(resolve(overrides = listOf(withoutPrice)).known)
        assertNull(resolve(overrides = listOf(withoutPrice)).pricePerRequest)
    }

    @Test
    fun `legacy count mode uses stored per request price`() {
        val legacy =
            LegacyPriceSettings(
                billingMode = BillingMode.COUNT,
                pricePerRequest = 0.05,
            )

        val resolved = resolve(legacy = legacy)

        assertEquals(BillingMode.COUNT, resolved.billingMode)
        assertEquals(0.05, resolved.pricePerRequest!!, 1e-9)
        assertTrue(resolved.known)
    }
}
