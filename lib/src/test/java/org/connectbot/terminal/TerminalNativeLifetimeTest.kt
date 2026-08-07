package org.connectbot.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * #509: Haven died with a native `SIGSEGV`/`SEGV_ACCERR` on the main thread
 * moments after a session ended. `SEGV_ACCERR` — mapped address, forbidden
 * access — is what freed-and-reprotected memory reports, not what a null
 * pointer reports.
 *
 * `TerminalNative` held the C++ terminal as a plain `Long` and every method
 * read it twice, once to check it and once to pass it, with `nativeDestroy`
 * reachable from a third thread in between. The native `Terminal` has its own
 * mutex, but that mutex is a *member* of the object being deleted, so it can
 * serialise calls against each other and never against the delete.
 *
 * What these pin is the one property that makes the pointer safe to pass:
 * **destruction cannot overlap a call.** They are written against the seam a
 * callback gives us — a callback runs from inside a live native call — rather
 * than against the garbage collector, because a test that waits for a
 * finalizer proves nothing on the run where the collector does not fire.
 */
class TerminalNativeLifetimeTest {

    private companion object {
        /** The callback arms on this thread and no other; see the damage override. */
        const val WRITER = "writer"
    }

    /** Records what came back; every method has to return something. */
    private open class NoopCallbacks : TerminalCallbacks {
        override fun damage(startRow: Int, endRow: Int, startCol: Int, endCol: Int) = 1
        override fun moverect(dest: TermRect, src: TermRect) = 1
        override fun moveCursor(pos: CursorPosition, oldPos: CursorPosition, visible: Boolean) = 1
        override fun setTermProp(prop: Int, value: TerminalProperty) = 1
        override fun bell() = 1
        override fun pushScrollbackLine(cols: Int, cells: Array<ScreenCell>, softWrapped: Boolean) = 1
        override fun popScrollbackLine(cols: Int, cells: Array<ScreenCell>) = 1
        override fun clearScrollback() = 1
        override fun onKeyboardInput(data: ByteArray) = data.size
        override fun onOscSequence(command: Int, payload: String, cursorRow: Int, cursorCol: Int) = 1
    }

    /**
     * The crash, reproduced at the seam where it happens.
     *
     * A callback runs on the calling thread from *inside* `vterm_input_write`,
     * so while one is executing there is provably a live native call on the
     * stack. Closing from another thread at that instant is exactly the
     * interleaving that deleted the terminal underneath #509's reporter.
     *
     * Before the fix `close()` took no lock, so the closing thread ran straight
     * through and `delete term` landed under a running `vterm_input_write` —
     * this assertion fails, and on an unlucky run the JVM dies of the same
     * SIGSEGV the reporter saw. After it, `close()` waits for the monitor.
     */
    @Test
    fun `close cannot run while a native call is in flight`() {
        val insideCallback = CountDownLatch(1)
        val callbackMayReturn = CountDownLatch(1)
        lateinit var terminal: TerminalNative

        val callbacks = object : NoopCallbacks() {
            override fun damage(startRow: Int, endRow: Int, startCol: Int, endCol: Int): Int {
                // Arm on the writer thread ONLY. `resize` below also damages the
                // screen, on this thread — the first version of this test parked
                // there instead, so by the time the closer ran the writer had
                // long finished and the assertion failed against a correct fix.
                if (Thread.currentThread().name == WRITER && insideCallback.count > 0) {
                    insideCallback.countDown()
                    callbackMayReturn.await(5, TimeUnit.SECONDS)
                }
                return 1
            }
        }

        terminal = TerminalNative(callbacks)
        terminal.resize(24, 80)

        val writer = thread(name = WRITER) {
            terminal.writeInput("hello #509\r\n".toByteArray())
        }

        assertTrue(
            "precondition: a damage callback should have fired from inside writeInput " +
                "on the writer thread — without it there is no live native call to race",
            insideCallback.await(5, TimeUnit.SECONDS),
        )

        // A live native call is now on `writer`'s stack, parked in the callback.
        val closer = thread(name = "closer") { terminal.close() }

        // The property: close() must not get through while that call is live.
        // Generous, because a false pass here would be a test that cannot fail.
        closer.join(500)
        val closedDuringCall = !closer.isAlive

        // Release the writer regardless, so a failure reports rather than hangs.
        callbackMayReturn.countDown()
        writer.join(5_000)
        closer.join(5_000)

        assertTrue(
            "close() freed the native terminal while a native call was still on " +
                "another thread's stack — this is #509's use-after-free",
            !closedDuringCall,
        )
    }

    /** Once closed, calls must be refused rather than reaching native with a dead pointer. */
    @Test
    fun `every entry point refuses to run after close`() {
        val terminal = TerminalNative(NoopCallbacks())
        terminal.resize(24, 80)
        terminal.close()

        assertThrows(IllegalStateException::class.java) { terminal.writeInput("x".toByteArray()) }
        assertThrows(IllegalStateException::class.java) { terminal.resize(10, 10) }
        assertThrows(IllegalStateException::class.java) { terminal.dispatchKey(0, VTermKey.ENTER) }
        assertThrows(IllegalStateException::class.java) { terminal.dispatchCharacter(0, 'a'.code) }
        assertThrows(IllegalStateException::class.java) { terminal.getCellRun(0, 0, CellRun()) }
        assertThrows(IllegalStateException::class.java) { terminal.getLineContinuation(0) }
        assertThrows(IllegalStateException::class.java) { terminal.setDefaultColors(0, 0) }
        assertThrows(IllegalStateException::class.java) { terminal.setBoldHighbright(true) }
        assertThrows(IllegalStateException::class.java) { terminal.setPaletteColors(IntArray(16)) }
    }

    /**
     * `finalize()` is the only thing in this library that ever frees the native
     * terminal, and a failsafe that double-frees is worse than no failsafe: the
     * second `delete` is on a pointer the first one returned to the allocator.
     */
    @Test
    fun `close is idempotent`() {
        val terminal = TerminalNative(NoopCallbacks())
        terminal.resize(24, 80)
        terminal.close()
        terminal.close()
        terminal.close()
        assertThrows(IllegalStateException::class.java) { terminal.resize(10, 10) }
    }

    /**
     * Hammering close against live calls must never reach native with a freed
     * pointer. Probabilistic by nature — it is a race — so it is here to catch
     * a regression over many runs, not to prove correctness on one. The
     * deterministic proof is the first test.
     */
    @Test
    fun `concurrent calls and close never reach native with a dead pointer`() {
        repeat(50) {
            val terminal = TerminalNative(NoopCallbacks())
            terminal.resize(24, 80)
            val start = CountDownLatch(1)
            var unexpected: Throwable? = null

            val workers = (1..4).map { n ->
                thread(name = "worker-$n") {
                    start.await()
                    repeat(200) {
                        try {
                            terminal.writeInput("line $n\r\n".toByteArray())
                        } catch (expected: IllegalStateException) {
                            return@thread // closed underneath us: the contract
                        } catch (t: Throwable) {
                            unexpected = t
                            return@thread
                        }
                    }
                }
            }
            val closer = thread(name = "closer") {
                start.await()
                terminal.close()
            }

            start.countDown()
            workers.forEach { it.join(10_000) }
            closer.join(10_000)

            assertEquals("only IllegalStateException is an acceptable loss", null, unexpected)
        }
    }
}
