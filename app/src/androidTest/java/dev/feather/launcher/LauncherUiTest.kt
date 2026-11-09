package dev.feather.launcher

import android.app.Instrumentation
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView

/** Platform-only runner: exercises real pointer input without a test dependency. */
class LauncherUiTest : Instrumentation() {
    private lateinit var activity: MainActivity
    private var checks = 0

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val prefs = Prefs.layout(targetContext)
        val saved = prefs.all
        val result = Bundle()
        var resultCode = Activity.RESULT_OK
        try {
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            waitForIdleSync()
            val deadline = SystemClock.uptimeMillis() + 5000
            var focused = false
            while (!focused && SystemClock.uptimeMillis() < deadline) {
                main { focused = activity.hasWindowFocus() }
                SystemClock.sleep(50)
            }
            check(focused) { "Launcher window has no input focus; unlock the emulator" }
            val apps = Apps.list.take(2)
            check(apps.size == 2) { "Two installed apps required" }
            val first = apps[0].key
            val second = apps[1].key
            main {
                set("animate", false)
                set("lowMem", false)
                set("homeMap", mutableMapOf(0 to first, 1 to second))
                set("dockMap", mutableMapOf<Int, String>())
                set("folders", mutableMapOf<Int, Folder>())
                call("saveAndRebind", false)
            }
            waitForIdleSync()

            drag(0, 1, 0)
            main {
                check(home() == mapOf(0 to second, 1 to first)) { "Quick drop must swap" }
                check(folders().isEmpty()) { "Quick drop created a folder" }
            }
            passed("quick drop swaps without grouping")
            undo()
            main { check(home() == mapOf(0 to first, 1 to second)) { "Undo did not restore swap" } }
            passed("Undo restores swapped cells")

            drag(0, 1, 700)
            main {
                val folder = folders().values.single()
                check(folder.keys.toSet() == setOf(first, second)) { "Folder contents wrong" }
                check(home() == mapOf(1 to folder.key)) { "Folder placement wrong" }
            }
            passed("held drop creates folder")
            undo()
            main {
                check(home() == mapOf(0 to first, 1 to second)) { "Undo did not restore folder layout" }
                check(folders().isEmpty()) { "Undo left orphan folder" }
            }
            passed("Undo restores folder contents and layout")

            main {
                call("openDrawer", false)
                val search = field<EditText>("search")
                for (query in listOf(apps[1].label, apps[0].label.take(2), apps[1].label.takeLast(3))) {
                    search.setText(query)
                    val q = normalize(query.trim())
                    val shown = field<List<Any>>("shown").filterIsInstance<App>()
                    val expected = Apps.list.filter { q in it.search }.sortedBy { searchRank(it.search, q) }
                    check(shown.map { it.key } == expected.map { it.key }) { "Wrong search order: $query" }
                }
                call("closeDrawer", false)
            }
            passed("drawer search renders ranked matches")
            main { check(home() == mapOf(0 to first, 1 to second)) { "Search changed layout" } }
            passed("search preserves home layout")
            main { call("openDrawer", false) }
            waitForIdleSync()
            drag(0, 1, 700, drawer = true)
            main {
                val folder = folders().values.single()
                check(folder.drawer && folder.keys.toSet() == setOf(first, second)) { "Drawer grouping failed" }
            }
            passed("held drawer drop groups apps")
            undo()
            main {
                check(folders().isEmpty()) { "Undo left drawer folder" }
                check(field<List<Any>>("shown").filterIsInstance<App>().map { it.key }.containsAll(listOf(first, second))) {
                    "Undo did not restore drawer apps"
                }
                call("closeDrawer", false)
            }
            passed("Undo restores grouped drawer apps")
            result.putString("stream", "\n$checks UI checks passed\n")
        } catch (failure: Throwable) {
            result.putString("stream", "\nFAILED after $checks checks: ${failure.stackTraceToString()}\n")
            resultCode = Activity.RESULT_CANCELED
            sendStatus(1, status("UI checks"))
            sendStatus(-2, status("UI checks").apply { putString("stack", failure.stackTraceToString()) })
        } finally {
            main { if (::activity.isInitialized) activity.finish() }
            prefs.edit().clear().apply {
                for ((key, value) in saved) putString(key, value as String)
            }.commit()
        }
        finish(resultCode, result)
    }

    private fun passed(name: String) {
        checks++
        val status = status(name)
        sendStatus(1, status)
        sendStatus(0, status.apply { putString("stream", "PASS: $name\n") })
    }

    private fun status(name: String) = Bundle().apply {
        putString("class", LauncherUiTest::class.java.name)
        putString("test", name)
        putInt("numtests", 8)
        putInt("current", checks)
    }

    private fun main(block: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync { try { block() } catch (caught: Throwable) { failure = caught } }
        failure?.let { throw it }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T = MainActivity::class.java.getDeclaredField(name).apply {
        isAccessible = true
    }.get(activity) as T

    private fun set(name: String, value: Any) = MainActivity::class.java.getDeclaredField(name).apply {
        isAccessible = true
    }.set(activity, value)

    private fun call(name: String, value: Boolean) = MainActivity::class.java
        .getDeclaredMethod(name, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        .invoke(activity, value)

    private fun home(): Map<Int, String> = field("homeMap")
    private fun folders(): Map<Int, Folder> = field("folders")

    private fun undo() {
        main {
            val undo = field<TextView>("undo")
            check(undo.visibility == View.VISIBLE) { "Undo button missing" }
            check(undo.performClick()) { "Undo button has no action" }
            check(undo.visibility == View.GONE) { "Undo should be single use" }
        }
        waitForIdleSync()
    }

    private fun drag(from: Int, to: Int, holdMs: Long, drawer: Boolean = false) {
        val points = Array(2) { IntArray(2) }
        val deadline = SystemClock.uptimeMillis() + 5000
        var laidOut = false
        while (!laidOut && SystemClock.uptimeMillis() < deadline) {
            main {
                val grid = if (drawer) field<android.widget.GridView>("grid") else field<CellGrid>("home")
                laidOut = grid.childCount >= 2 && (0 until grid.childCount).all {
                    grid.getChildAt(it).height > 0 && !grid.getChildAt(it).isLayoutRequested
                }
            }
            SystemClock.sleep(50)
        }
        check(laidOut) { "Home icons never laid out" }
        main {
            val home = if (drawer) field<android.widget.GridView>("grid") else field<CellGrid>("home")
            for ((index, cell) in listOf(from, to).withIndex()) {
                val icon = if (drawer) home.getChildAt(cell) as IconView else
                    (0 until home.childCount).map { home.getChildAt(it) as IconView }.first { it.cell == cell }
                icon.getLocationOnScreen(points[index])
                points[index][0] += icon.width / 2
                points[index][1] += icon.height / 2
            }
        }
        val down = SystemClock.uptimeMillis()
        fun event(action: Int, x: Int, y: Int) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            check(uiAutomation.injectInputEvent(event, true)) { "Touch injection failed" }
            event.recycle()
        }
        event(MotionEvent.ACTION_DOWN, points[0][0], points[0][1])
        SystemClock.sleep(700)
        for (step in 1..8) {
            event(MotionEvent.ACTION_MOVE,
                points[0][0] + (points[1][0] - points[0][0]) * step / 8,
                points[0][1] + (points[1][1] - points[0][1]) * step / 8)
            SystemClock.sleep(16)
        }
        SystemClock.sleep(holdMs)
        if (holdMs > 0) main { check(field<Boolean>("folderReady")) { "Folder preview never became ready" } }
        event(MotionEvent.ACTION_UP, points[1][0], points[1][1])
        SystemClock.sleep(250)
        waitForIdleSync()
    }
}
