/*
 * ConnectBot Terminal
 * Copyright 2025 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.connectbot.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The terminal window title (libvterm's VTERM_PROP_TITLE) must reach
 * consumers reactively through [TerminalEmulator.terminalTitle] — Haven's
 * tab label (#625) collects this flow rather than intercepting the OSC
 * sequences from the byte stream, so a title-only update must be visible
 * there without waiting for a screen repaint.
 */
@RunWith(AndroidJUnit4::class)
class TerminalTitleTest {

    private fun newEmulator(): TerminalEmulator =
        TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80)

    /** Drive the async pipeline the way the other emulator tests do: idle the
     *  instrumentation looper, then flush the emulator's pending updates. */
    private fun drain(emulator: TerminalEmulator) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        (emulator as TerminalEmulatorImpl).processPendingUpdates()
    }

    @Test
    fun titleStartsEmpty() = runBlocking {
        val emulator = newEmulator()
        delay(10)
        drain(emulator)
        assertEquals("", (emulator as TerminalEmulatorImpl).terminalTitle.value)
    }

    @Test
    fun osc0SetsTheTitle() = runBlocking {
        val emulator = newEmulator()
        emulator.writeInput("\u001B]0;My Title\u0007".toByteArray())
        drain(emulator)
        val impl = emulator as TerminalEmulatorImpl
        assertEquals("My Title", impl.terminalTitle.value)
    }

    @Test
    fun osc2SetsTheTitle() = runBlocking {
        val emulator = newEmulator()
        emulator.writeInput("\u001B]2;Second Title\u0007".toByteArray())
        drain(emulator)
        assertEquals("Second Title", (emulator as TerminalEmulatorImpl).terminalTitle.value)
    }

    @Test
    fun titleUpdatesInPlace() = runBlocking {
        val emulator = newEmulator()
        emulator.writeInput("\u001B]0;First\u0007".toByteArray())
        drain(emulator)
        val impl = emulator as TerminalEmulatorImpl
        assertEquals("First", impl.terminalTitle.value)
        // CLI agents retitile continuously as they work — the latest title
        // must win, not the first.
        emulator.writeInput("\u001B]2;Second\u0007".toByteArray())
        drain(emulator)
        assertEquals("Second", impl.terminalTitle.value)
    }

    @Test
    fun emptyTitleIsAnEmptyStringNotThePreviousOne() = runBlocking {
        val emulator = newEmulator()
        emulator.writeInput("\u001B]0;Gone\u0007".toByteArray())
        drain(emulator)
        val impl = emulator as TerminalEmulatorImpl
        assertEquals("Gone", impl.terminalTitle.value)
        // OSC 0 with an empty payload resets the title to empty.
        emulator.writeInput("\u001B]0;\u0007".toByteArray())
        drain(emulator)
        assertEquals("", impl.terminalTitle.value)
    }
}