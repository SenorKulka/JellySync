package org.jellyfin.playback.jellyfin.network

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.RawResponse
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import java.util.concurrent.Executors

class ReliableApiClientTests : FunSpec({
	test("all subscribers share the replacement socket and credentials change before reconnect") {
		val delegate = mockk<ApiClient>()
		val socket = mockk<ReliableSocketApi>()
		val actions = mutableListOf<String>()
		val clientInfo = ClientInfo("TV", "test")
		val deviceInfo = DeviceInfo("test-device", "TV")
		var token: String? = "old-token"
		every { delegate.accessToken } answers { token }
		every { delegate.update("https://example.invalid", "new-token", clientInfo, deviceInfo) } answers {
			token = secondArg()
			actions.add("credentials")
		}
		val client = ReliableApiClient(delegate) { socket }
		every { socket.notifyApiClientUpdate() } answers {
			client.accessToken shouldBe "new-token"
			actions.add("reconnect")
		}

		client.webSocket shouldBe socket
		client.webSocket shouldBe socket
		client.update("https://example.invalid", "new-token", clientInfo, deviceInfo)

		actions shouldBe listOf("credentials", "reconnect")
		verify(exactly = 0) { delegate.webSocket }
	}

	test("HTTP requests and unused API updates do not initialize either socket") {
		val delegate = mockk<ApiClient>()
		val response = RawResponse(byteArrayOf(), 204, emptyMap())
		val clientInfo = ClientInfo("TV", "test")
		val deviceInfo = DeviceInfo("test-device", "TV")
		val client = ReliableApiClient(delegate) { error("HTTP-only clients must not open a socket") }
		every { delegate.update(null, null, clientInfo, deviceInfo) } returns Unit
		coEvery { delegate.request(HttpMethod.POST, "/SyncPlay/Pause", emptyMap(), emptyMap(), null) } returns response

		client.update(null, null, clientInfo, deviceInfo)
		client.request(HttpMethod.POST, "/SyncPlay/Pause") shouldBe response

		coVerify(exactly = 1) { delegate.request(HttpMethod.POST, "/SyncPlay/Pause", emptyMap(), emptyMap(), null) }
		verify(exactly = 0) { delegate.webSocket }
	}

	test("HTTP body work stays off the calling UI thread after suspension") {
		Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { uiDispatcher ->
			withContext(uiDispatcher) {
				val uiThread = Thread.currentThread()
				val delegate = mockk<ApiClient>()
				val response = RawResponse("clock".toByteArray(), 200, emptyMap())
				val client = ReliableApiClient(delegate) { error("HTTP-only clients must not open a socket") }
				coEvery { delegate.request(HttpMethod.GET, "/SyncPlay/Time", emptyMap(), emptyMap(), null) } coAnswers {
					Thread.currentThread() shouldNotBe uiThread
					delay(1)
					Thread.currentThread() shouldNotBe uiThread
					response
				}

				client.request(HttpMethod.GET, "/SyncPlay/Time") shouldBe response
				Thread.currentThread() shouldBe uiThread
			}
		}
	}
})
