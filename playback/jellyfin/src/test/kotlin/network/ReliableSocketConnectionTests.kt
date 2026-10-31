@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.jellyfin.playback.jellyfin.network

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.sockets.SocketApiState
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.OutboundWebSocketMessage
import org.jellyfin.sdk.model.api.SendCommandType
import org.jellyfin.sdk.model.api.SyncPlayCommandMessage
import org.jellyfin.sdk.model.api.SyncPlayGroupJoinedUpdate
import org.jellyfin.sdk.model.api.SyncPlayGroupUpdateMessage

class ReliableSocketConnectionTests : FunSpec({
	test("the handshake uses only its credential snapshot, never the API client's current values") {
		runTest {
			val fixture = SocketFixture(this)
			// The API client has already switched to another server since the snapshot was taken.
			fixture.switchApiToOtherServer()
			val request = slot<Request>()
			every { fixture.factory.newWebSocket(capture(request), capture(fixture.listener)) } returns fixture.webSocket
			fixture.connect()
			request.captured.url.toString() shouldBe "https://jellyfin.example.test/socket"
			val authorization = request.captured.header("Authorization").orEmpty()
			authorization.contains("test-token") shouldBe true
			authorization.contains("other-token") shouldBe false
			authorization.contains("other-device") shouldBe false
			fixture.socket.close()
		}
	}

	test("serialized GroupJoined and Stop in one startup burst both reach the typed consumer") {
		runTest {
			val fixture = SocketFixture(this)
			val received = mutableListOf<OutboundWebSocketMessage>()
			backgroundScope.launch { fixture.socket.messages.collect { received += it } }
			val connecting = async { fixture.socket.connect(backgroundScope, fixture.credentials) }
			runCurrent()
			// Deliberately do not yield between callbacks, reproducing the real server's burst.
			fixture.deliver(GROUP_JOINED)
			fixture.deliver(command("Stop", 0))
			repeat(40) { fixture.deliver(command("Seek", it.toLong())) }
			runCurrent()
			connecting.await()
			received.size shouldBe 42
			received[0].shouldBeInstanceOf<SyncPlayGroupUpdateMessage>().data.shouldBeInstanceOf<SyncPlayGroupJoinedUpdate>()
			received[1].shouldBeInstanceOf<SyncPlayCommandMessage>().data?.command shouldBe SendCommandType.STOP
			received.drop(2).map { (it as SyncPlayCommandMessage).data?.positionTicks } shouldBe (0L until 40L).toList()
			fixture.socket.close()
		}
	}

	test("a stalled consumer exhausts a bounded buffer and disconnects instead of dropping commands") {
		runTest {
			val fixture = SocketFixture(this)
			val blocked = CompletableDeferred<Unit>()
			backgroundScope.launch { fixture.socket.messages.collect { blocked.await() } }
			fixture.connect()
			fixture.deliver(GROUP_JOINED)
			runCurrent()
			repeat(300) { fixture.deliver(command("Seek", it.toLong())) }
			runCurrent()
			fixture.socket.state.value.shouldBeInstanceOf<SocketApiState.Disconnected>().error?.message shouldBe
				"SyncPlay socket message buffer exhausted"
			verify { fixture.webSocket.cancel() }
		}
	}

	test("late frames and failures from the previous connection cannot affect a new session") {
		runTest {
			val fixture = SocketFixture(this)
			val received = mutableListOf<OutboundWebSocketMessage>()
			backgroundScope.launch { fixture.socket.messages.collect { received += it } }
			fixture.connect()
			val oldListener = fixture.listener.captured
			fixture.socket.close()
			fixture.connect()
			oldListener.onMessage(fixture.webSocket, GROUP_JOINED)
			oldListener.onFailure(fixture.webSocket, IllegalStateException("old session"), null)
			runCurrent()
			received.size shouldBe 0
			fixture.socket.state.value shouldBe SocketApiState.Connected
			fixture.socket.close()
		}
	}

	test("keepalive replies renew the timeout and a silent connection is detached") {
		runTest {
			val fixture = SocketFixture(this)
			fixture.connect()
			verify { fixture.webSocket.send(match<String> { it.contains("KeepAlive") }) }
			advanceTimeBy(3000)
			fixture.deliver(KEEP_ALIVE)
			runCurrent()
			advanceTimeBy(3000)
			runCurrent()
			fixture.socket.state.value shouldBe SocketApiState.Connected
			advanceTimeBy(1000)
			runCurrent()
			fixture.socket.state.value.shouldBeInstanceOf<SocketApiState.Disconnected>().error?.message shouldBe
				"SyncPlay keepalive reply timed out"
		}
	}
})

private class SocketFixture(private val scope: TestScope) {
	private val api = mockk<ApiClient>()
	val factory = mockk<WebSocket.Factory>()
	val webSocket = mockk<WebSocket>(relaxed = true)
	val listener = slot<WebSocketListener>()
	val socket = ReliableSocketConnection(api, factory)
	val credentials = SocketCredentials(
		"https://jellyfin.example.test/socket",
		"test-token",
		ClientInfo("TV", "test"),
		DeviceInfo("tv-device", "Test TV"),
	)

	init {
		every { api.createUrl("/socket", any(), any(), any()) } returns "https://jellyfin.example.test/socket"
		every { api.clientInfo } returns ClientInfo("TV", "test")
		every { api.deviceInfo } returns DeviceInfo("tv-device", "Test TV")
		every { api.accessToken } returns "test-token"
		every { factory.newWebSocket(any<Request>(), capture(listener)) } returns webSocket
		every { webSocket.send(any<String>()) } returns true
	}

	fun switchApiToOtherServer() {
		every { api.createUrl("/socket", any(), any(), any()) } returns "https://other.example.test/socket"
		every { api.deviceInfo } returns DeviceInfo("other-device", "Other TV")
		every { api.accessToken } returns "other-token"
	}

	suspend fun connect() {
		val connecting = scope.async { socket.connect(scope.backgroundScope, credentials) }
		scope.runCurrent()
		deliver(FORCE_KEEP_ALIVE)
		scope.runCurrent()
		connecting.await()
	}

	fun deliver(raw: String) = listener.captured.onMessage(webSocket, raw)
}

private const val GROUP_JOINED = """{
  "MessageType":"SyncPlayGroupUpdate",
  "Data":{
    "GroupId":"00000000000000000000000000000001","Type":"GroupJoined",
    "Data":{
      "GroupId":"00000000000000000000000000000001","GroupName":"Movie night",
      "State":"Idle","Participants":["Viewer"],"LastUpdatedAt":"2026-09-09T19:42:22.435Z"
    }
  },
  "MessageId":"00000000000000000000000000000002"
}"""
private const val FORCE_KEEP_ALIVE = """{"MessageType":"ForceKeepAlive","Data":2,"MessageId":"00000000000000000000000000000002"}"""
private const val KEEP_ALIVE = """{"MessageType":"KeepAlive","MessageId":"00000000000000000000000000000002"}"""

private fun command(type: String, ticks: Long) = """{
  "MessageType":"SyncPlayCommand",
  "Data":{
    "GroupId":"00000000000000000000000000000001","PlaylistItemId":"00000000000000000000000000000000",
    "When":"2026-09-09T19:42:22.435Z","PositionTicks":$ticks,"Command":"$type","EmittedAt":"2026-09-09T19:42:22.435Z"
  },
  "MessageId":"00000000000000000000000000000002"
}"""
