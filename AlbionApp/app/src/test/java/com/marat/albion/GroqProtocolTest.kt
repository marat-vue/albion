package com.marat.albion

import com.marat.albion.data.ai.AiException
import com.marat.albion.data.ai.ChatMessage
import com.marat.albion.data.ai.GroqModel
import com.marat.albion.data.ai.GroqProtocol
import com.marat.albion.data.ai.ModelLadder
import com.marat.albion.data.ai.ModelUnavailableException
import com.marat.albion.data.ai.RateLimitException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GroqProtocolTest {
    private val messages = listOf(ChatMessage.system("Be kind."), ChatMessage.user("Hi \"there\"\n\u0000"))

    @Test fun `request carries roles, limits and hidden reasoning for reasoning models`() {
        val json = Json.parseToJsonElement(GroqProtocol.request(GroqModel.GPT_OSS_120B, messages, 200, 0.7f, stream = true)).jsonObject
        assertEquals("openai/gpt-oss-120b", json["model"]!!.jsonPrimitive.content)
        assertEquals(true, json["stream"]!!.jsonPrimitive.boolean)
        assertEquals(200 + GroqProtocol.REASONING_ROOM, json["max_completion_tokens"]!!.jsonPrimitive.int)
        assertEquals("low", json["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals(false, json["include_reasoning"]!!.jsonPrimitive.boolean)
        val sent = json["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user"), sent.map { it["role"]!!.jsonPrimitive.content })
        assertEquals("Hi \"there\"\n", sent[1]["content"]!!.jsonPrimitive.content)
    }

    @Test fun `stream lines yield text pieces and skip the rest`() {
        assertEquals("Hel", GroqProtocol.delta("""data: {"choices":[{"index":0,"delta":{"content":"Hel"}}]}"""))
        assertEquals("лo", GroqProtocol.delta("""data:{"choices":[{"delta":{"content":"лo"}}]}"""))
        assertNull(GroqProtocol.delta("""data: {"choices":[{"delta":{"role":"assistant"}}]}"""))
        assertNull(GroqProtocol.delta("""data: {"choices":[{"delta":{"content":null},"finish_reason":"stop"}]}"""))
        assertNull(GroqProtocol.delta("data: [DONE]"))
        assertNull(GroqProtocol.delta(": keep-alive"))
        assertNull(GroqProtocol.delta(""))
    }

    @Test fun `error inside the stream becomes a readable exception`() {
        try {
            GroqProtocol.delta("""data: {"error":{"message":"Ошибка сервера"}}""")
            fail("Error was ignored")
        } catch (expected: AiException) { assertEquals("Ошибка сервера", expected.message) }
    }

    @Test fun `full response content is extracted`() {
        assertEquals("OK", GroqProtocol.content("""{"choices":[{"message":{"role":"assistant","content":"OK"}}]}"""))
        // Пустой ответ и чужая страница (например, от VPN) — понятная ошибка, а не сбой разбора.
        for (body in listOf("""{"choices":[]}""", "<html>Access denied</html>", "[]")) {
            try { GroqProtocol.content(body); fail("Accepted: $body") }
            catch (expected: AiException) { assertTrue(expected.message!!.any { it in 'А'..'я' }) }
        }
    }

    @Test fun `http errors explain what to do in Russian`() {
        assertTrue(GroqProtocol.error(401, "").message!!.contains("Ключ"))
        assertTrue(GroqProtocol.error(403, "").message!!.contains("VPN"))
        assertTrue(GroqProtocol.error(429, """{"error":{"message":"Rate limit reached"}}""").message!!.contains("Лимит"))
        assertTrue(GroqProtocol.error(400, """{"error":{"message":"Please reduce the length, context_length_exceeded"}}""")
            .message!!.contains("длинный"))
        assertTrue(GroqProtocol.error(503, "not json").message!!.contains("недоступен"))
    }

    @Test fun `ladder goes down while limits are used up and back up as they recover`() {
        val now = 1_000_000L
        assertEquals(GroqModel.GPT_OSS_120B, ModelLadder.pick(emptyMap(), now))
        val rest = mutableMapOf(GroqModel.GPT_OSS_120B.id to now + 3_600_000)
        assertEquals(GroqModel.QWEN_27B, ModelLadder.pick(rest, now))
        rest[GroqModel.QWEN_27B.id] = now + 60_000
        assertEquals(GroqModel.GPT_OSS_20B, ModelLadder.pick(rest, now))
        rest[GroqModel.GPT_OSS_20B.id] = now + 60_000
        assertEquals(GroqModel.ALLAM_7B, ModelLadder.pick(rest, now))
        rest[GroqModel.ALLAM_7B.id] = now + 120_000
        assertNull(ModelLadder.pick(rest, now))
        assertEquals(now + 60_000, ModelLadder.nextFree(rest, now))
        // Через минуту вернулись средние модели, через час — самая сильная.
        assertEquals(GroqModel.QWEN_27B, ModelLadder.pick(rest, now + 60_000))
        assertEquals(GroqModel.GPT_OSS_120B, ModelLadder.pick(rest, now + 3_600_000))
    }

    @Test fun `summaries skip the weak model and wait for a strong one`() {
        val now = 1_000_000L
        val strongResting = GroqModel.entries.filter { it.strong }.associate { it.id to now + 60_000 }
        assertEquals(GroqModel.ALLAM_7B, ModelLadder.pick(strongResting, now))
        assertNull(ModelLadder.pick(strongResting, now, strongOnly = true))
        assertEquals(GroqModel.GPT_OSS_120B, ModelLadder.pick(strongResting, now + 60_000, strongOnly = true))
        assertEquals(GroqModel.QWEN_27B, ModelLadder.pick(mapOf(GroqModel.GPT_OSS_120B.id to now + 1), now, strongOnly = true))
    }

    @Test fun `rate limit wait comes from the header or the error text`() {
        assertEquals(7_000L, GroqProtocol.waitMs("7", ""))
        assertEquals(432_500L, GroqProtocol.waitMs(null, "Rate limit reached on tokens per day (TPD). Please try again in 7m12.5s. Visit"))
        assertEquals(3_723_000L, GroqProtocol.waitMs(null, "Please try again in 1h2m3s."))
        assertEquals(GroqProtocol.DEFAULT_WAIT_MS, GroqProtocol.waitMs(null, "Rate limit reached"))
        assertEquals(179_560L, GroqProtocol.duration("2m59.56s"))
        assertEquals(850L, GroqProtocol.duration("850ms"))
        val error = GroqProtocol.error(429, """{"error":{"message":"Please try again in 2m0s."}}""", null)
        assertEquals(120_000L, (error as RateLimitException).waitMs)
    }

    @Test fun `missing model is skipped instead of failing the request`() {
        assertTrue(GroqProtocol.error(404, "") is ModelUnavailableException)
        assertTrue(GroqProtocol.error(400, """{"error":{"message":"The model `x` has been decommissioned"}}""") is ModelUnavailableException)
        assertFalse(GroqProtocol.error(401, "") is ModelUnavailableException)
    }

    @Test fun `qwen gets reasoning switched off and allam none`() {
        val qwen = Json.parseToJsonElement(GroqProtocol.request(GroqModel.QWEN_27B, messages, 200, 0f, stream = false)).jsonObject
        assertEquals("none", qwen["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals(200, qwen["max_completion_tokens"]!!.jsonPrimitive.int)
        assertNull(qwen["include_reasoning"])
        val allam = Json.parseToJsonElement(GroqProtocol.request(GroqModel.ALLAM_7B, messages, 200, 0f, stream = false)).jsonObject
        assertNull(allam["reasoning_effort"])
    }

    @Test fun `small context model gets only the recent turns`() {
        val long = listOf(ChatMessage.system("Rules.")) +
            (1..40).map { if (it % 2 == 1) ChatMessage.user("Message $it " + "word ".repeat(80)) else ChatMessage.assistant("Reply $it " + "word ".repeat(80)) }
        val fitted = GroqProtocol.fit(GroqModel.ALLAM_7B, long, 300)
        assertEquals("system", fitted.first().role)
        assertEquals(long.last(), fitted.last())
        assertTrue(fitted.size < long.size)
        assertEquals(long, GroqProtocol.fit(GroqModel.GPT_OSS_120B, long, 300))
    }
}
