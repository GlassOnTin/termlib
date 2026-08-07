package org.connectbot.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * #509: `TerminalEmulator` had no `close()`, so the only thing that ever freed
 * the native terminal was a finalizer. That is two problems — the memory is
 * held until the collector happens to run, and the collector will happily run
 * while a native call is on the stack.
 *
 * A `close()` that throws on the losing side of a race would be no better than
 * the leak: the threads still calling in are transports delivering their last
 * bytes as the UI tears the screen down, and an exception there unwinds a
 * reader thread rather than the UI's. So the contract these pin is: closing is
 * idempotent, releases the native terminal, and makes every subsequent call a
 * silent no-op from any thread.
 */
@RunWith(AndroidJUnit4::class)
class TerminalEmulatorCloseTest {

    private fun idle() = InstrumentationRegistry.getInstrumentation().waitForIdleSync()

    private fun snapshotText(e: TerminalEmulatorImpl): String {
        idle()
        e.processPendingUpdates()
        return e.snapshot.value.lines.joinToString("\n") { it.text.trimEnd() }.trimEnd()
    }

    @Test
    fun `writing after close is ignored rather than thrown`() {
        val emulator = TerminalEmulatorFactory.create(initialRows = 10, initialCols = 40)
            as TerminalEmulatorImpl
        emulator.writeInput("before close\r\n".toByteArray())
        val before = snapshotText(emulator)
        assertNotEquals("precondition: the terminal should have painted something", "", before)

        emulator.close()

        // None of these may throw. Each one reaches a different native entry
        // point, so a guard missing from any single one shows up here.
        emulator.writeInput("after close\r\n".toByteArray())
        emulator.resize(20, 60)
        emulator.dispatchKey(0, VTermKey.ENTER)
        emulator.dispatchCharacter(0, 'x'.code)
        emulator.clearScreen()
        emulator.processPendingUpdates()
    }

    @Test
    fun `close is idempotent`() {
        val emulator = TerminalEmulatorFactory.create(initialRows = 10, initialCols = 40)
        emulator.writeInput("hello\r\n".toByteArray())
        emulator.close()
        emulator.close()
        emulator.close()
    }

    /**
     * An emulator that was created and dropped without ever being written to
     * has no native terminal yet — it is built lazily. Closing must not
     * construct one purely so it can destroy it.
     */
    @Test
    fun `closing an unused emulator does not build a native terminal`() {
        val emulator = TerminalEmulatorFactory.create(initialRows = 10, initialCols = 40)
        emulator.close()
        // Still a no-op afterwards rather than lazily waking up.
        emulator.writeInput("ignored\r\n".toByteArray())
    }

    /**
     * The disposal race this is really about: the UI closes while a transport
     * is mid-write. Before `close()` existed there was nothing to race; now
     * that there is, the losing side must lose quietly.
     */
    @Test
    fun `closing under a writing thread neither throws nor crashes`() {
        repeat(25) {
            val emulator = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)
            val start = CountDownLatch(1)
            // AtomicReference rather than @Volatile: the flag is written on the
            // writer thread and read here, and a local cannot be volatile.
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)

            val writer = thread(name = "transport") {
                start.await()
                repeat(400) { n ->
                    try {
                        emulator.writeInput("line $n\r\n".toByteArray())
                    } catch (t: Throwable) {
                        failure.set(t)
                        return@thread
                    }
                }
            }
            val closer = thread(name = "ui-dispose") {
                start.await()
                emulator.close()
            }

            start.countDown()
            writer.join(10_000)
            closer.join(10_000)

            assertEquals(
                "a transport writing into a terminal being closed must not see an exception",
                null,
                failure.get(),
            )
        }
    }

    /** Output written before the close is still readable; closing is not clearing. */
    @Test
    fun `close does not discard what was already painted`() {
        val emulator = TerminalEmulatorFactory.create(initialRows = 10, initialCols = 40)
            as TerminalEmulatorImpl
        emulator.writeInput("kept\r\n".toByteArray())
        val before = snapshotText(emulator)
        emulator.close()
        assertTrue("precondition", before.contains("kept"))
        assertEquals(
            "the last snapshot should survive the close",
            before,
            emulator.snapshot.value.lines.joinToString("\n") { it.text.trimEnd() }.trimEnd(),
        )
    }

    /**
     * `processPendingUpdates` is reachable from a Choreographer frame callback,
     * which `close()` cannot cancel — no reference is kept to remove it with,
     * and it is not in the Handler queue that close drains. So it has to defend
     * itself, and it rebuilds lines by reading cells out of the native
     * terminal.
     */
    @Test
    fun `a frame callback landing after close is harmless`() {
        val emulator = TerminalEmulatorFactory.create(initialRows = 10, initialCols = 40)
            as TerminalEmulatorImpl
        emulator.writeInput("damage me\r\n".toByteArray())
        // Damage is pending and a frame callback is queued at this point.
        emulator.close()
        idle()
        // Whatever was queued has now run; and an explicit call is safe too.
        emulator.processPendingUpdates()
    }

    /** Closing from a thread other than the one that built it must be fine. */
    @Test
    fun `close works from a background thread`() {
        val emulator = TerminalEmulatorFactory.create(initialRows = 10, initialCols = 40)
        emulator.writeInput("hello\r\n".toByteArray())
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        thread(name = "closer") {
            try {
                emulator.close()
            } catch (t: Throwable) {
                failure = t
            } finally {
                done.countDown()
            }
        }
        assertTrue("close should not block", done.await(5, TimeUnit.SECONDS))
        assertEquals(null, failure)
    }
}
