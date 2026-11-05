package expo.modules.screentime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The restart chain's wait at the lock screen ([UnlockWait]): the pure
 * state machine the controller drives on the main looper, with the clock
 * and the poll results injected. The outcomes it must produce: the unlock
 * within the window, the window elapsing at the lock screen, the screen
 * going off mid-wait, a second caller joining a running wait, and a
 * release mid-wait. Plus the bounds the design rests on.
 */
class UnlockWaitTest {
  private val keyguard = UnlockWait.KEYGUARD
  private val screenOff = UnlockWait.SCREEN_OFF

  private fun poll(wait: UnlockWait, atMs: Long, blocked: String? = keyguard, wanted: Boolean = true) = wait.poll(atMs, blocked, wanted)

  @Test
  fun `the unlock within the window ends the wait as UNLOCKED, then every waiter is told the revival's result once`() {
    val wait = UnlockWait(startedAtMs = 1_000, windowMs = UnlockWait.WINDOW_MS, pollMs = UnlockWait.POLL_MS)
    val told = mutableListOf<Boolean>()
    wait.join { told += it }
    // Polled every 250 ms while the keyguard holds.
    assertEquals(UnlockWait.Next.Poll(250), poll(wait, 1_000))
    assertEquals(UnlockWait.Next.Poll(250), poll(wait, 1_250))
    assertEquals(UnlockWait.Next.Poll(250), poll(wait, 5_000))
    assertNull(wait.outcome)
    // The keyguard gone with the screen on: revive now.
    assertEquals(UnlockWait.Next.End(UnlockWait.Outcome.UNLOCKED), poll(wait, 5_250, blocked = null))
    assertEquals(UnlockWait.Outcome.UNLOCKED, wait.outcome)
    assertTrue(told.isEmpty())
    // The revival ran: the waiter learns its result, once.
    wait.settle(true)
    assertEquals(listOf(true), told)
    wait.settle(true)
    wait.settle(false)
    assertEquals(listOf(true), told)
    assertTrue(wait.settled)
  }

  @Test
  fun `the window elapsing at the lock screen ends the wait as TIMED_OUT, with the last poll clipped to the window`() {
    val wait = UnlockWait(startedAtMs = 0, windowMs = UnlockWait.WINDOW_MS, pollMs = UnlockWait.POLL_MS)
    val told = mutableListOf<Boolean>()
    wait.join { told += it }
    val window = UnlockWait.WINDOW_MS
    assertEquals(UnlockWait.Next.Poll(250), poll(wait, window - 500))
    // 100 ms left: the next poll lands on the window, never past it.
    assertEquals(UnlockWait.Next.Poll(100), poll(wait, window - 100))
    assertEquals(UnlockWait.Next.End(UnlockWait.Outcome.TIMED_OUT), poll(wait, window))
    assertEquals(UnlockWait.Outcome.TIMED_OUT, wait.outcome)
    wait.settle(false)
    assertEquals(listOf(false), told)
  }

  @Test
  fun `a poll past the window is a timeout even when the driver was late`() {
    val wait = UnlockWait(startedAtMs = 0, windowMs = UnlockWait.WINDOW_MS, pollMs = UnlockWait.POLL_MS)
    assertEquals(UnlockWait.Next.End(UnlockWait.Outcome.TIMED_OUT), poll(wait, UnlockWait.WINDOW_MS + 15_000))
  }

  @Test
  fun `the screen going off mid-wait ends the wait as SCREEN_OFF at that poll`() {
    val wait = UnlockWait(startedAtMs = 0, windowMs = UnlockWait.WINDOW_MS, pollMs = UnlockWait.POLL_MS)
    val told = mutableListOf<Boolean>()
    wait.join { told += it }
    assertEquals(UnlockWait.Next.Poll(250), poll(wait, 250))
    assertEquals(UnlockWait.Next.End(UnlockWait.Outcome.SCREEN_OFF), poll(wait, 500, blocked = screenOff))
    assertEquals(UnlockWait.Outcome.SCREEN_OFF, wait.outcome)
    wait.settle(false)
    assertEquals(listOf(false), told)
  }

  @Test
  fun `a second caller joins the running wait and both are told the one outcome, in join order`() {
    val wait = UnlockWait(startedAtMs = 0, windowMs = UnlockWait.WINDOW_MS, pollMs = UnlockWait.POLL_MS)
    val told = mutableListOf<String>()
    wait.join { told += "screen-on:$it" }
    assertEquals(1, wait.joined)
    assertEquals(UnlockWait.Next.Poll(250), poll(wait, 0))
    // The watchdog lands 22 ms after the SCREEN_ON retry and finds the keyguard too.
    wait.join { told += "watchdog:$it" }
    assertEquals(2, wait.joined)
    assertEquals(UnlockWait.Next.Poll(250), poll(wait, 250))
    assertEquals(UnlockWait.Next.End(UnlockWait.Outcome.UNLOCKED), poll(wait, 2_000, blocked = null))
    wait.settle(true)
    assertEquals(listOf("screen-on:true", "watchdog:true"), told)
    assertEquals(0, wait.joined)
  }

  @Test
  fun `a release mid-wait ends the wait as RELEASED before the screen is looked at`() {
    val wait = UnlockWait(startedAtMs = 0, windowMs = UnlockWait.WINDOW_MS, pollMs = UnlockWait.POLL_MS)
    val told = mutableListOf<Boolean>()
    wait.join { told += it }
    assertEquals(UnlockWait.Next.Poll(250), poll(wait, 250))
    // Released and unlocked at the same poll: nothing is wanted, nothing is revived.
    assertEquals(UnlockWait.Next.End(UnlockWait.Outcome.RELEASED), poll(wait, 500, blocked = null, wanted = false))
    assertEquals(UnlockWait.Outcome.RELEASED, wait.outcome)
    // Nothing to reach: the caller is told the engine is in line with the store.
    wait.settle(true)
    assertEquals(listOf(true), told)
  }

  @Test
  fun `the keyguard back right after the unlock poll does not reopen the wait, the revival answers it`() {
    val wait = UnlockWait(startedAtMs = 0, windowMs = UnlockWait.WINDOW_MS, pollMs = UnlockWait.POLL_MS)
    assertEquals(UnlockWait.Next.End(UnlockWait.Outcome.UNLOCKED), poll(wait, 250, blocked = null))
    // The driver revives now; a re-lock makes its attempts fail and the watchdog retry as before. The
    // wait itself is over: polling it again is a programming error, not a second wait.
    assertTrue(runCatching { poll(wait, 500, blocked = keyguard) }.exceptionOrNull() is IllegalStateException)
    assertEquals(UnlockWait.Outcome.UNLOCKED, wait.outcome)
  }

  @Test
  fun `a wait cannot be polled after it ended nor joined after it settled`() {
    val wait = UnlockWait(startedAtMs = 0, windowMs = UnlockWait.WINDOW_MS, pollMs = UnlockWait.POLL_MS)
    poll(wait, 0, blocked = null)
    assertTrue(runCatching { poll(wait, 250) }.exceptionOrNull() is IllegalStateException)
    wait.settle(true)
    assertTrue(runCatching { wait.join { } }.exceptionOrNull() is IllegalStateException)
  }

  @Test
  fun `the window and the revival that follows it stay under half the background broadcast timeout`() {
    // The receiver keeps its broadcast open for the wait, then for the overlay attempts (the last one at
    // 1500 ms, ForegroundStartExemption.ATTEMPTS_MS) and a poll's slack; past BROADCAST_BG_TIMEOUT the
    // system finishes the receiver and ANRs the process. HW_TIMEOUT_MULTIPLIER only makes it longer.
    val longestAttemptMs = 1_500L
    assertTrue(UnlockWait.WINDOW_MS + UnlockWait.POLL_MS + longestAttemptMs + 1_000 < UnlockWait.BACKGROUND_BROADCAST_TIMEOUT_MS / 2)
    // An unlock is seen within a poll: a quarter second is "about 2 s" with the attempts' first tries.
    assertTrue(UnlockWait.POLL_MS <= 250)
    // The floor alarm armed before the wait (window + LOCKED_MS) lands after the wait and the attempts
    // that follow an unlock, so a wait that ends with the engine running spends no delivery.
    assertTrue(RestartBackoff.LOCKED_MS > longestAttemptMs + UnlockWait.POLL_MS)
    assertFalse(UnlockWait.KEYGUARD == UnlockWait.SCREEN_OFF)
  }
}
