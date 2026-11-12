package com.marat.albion

import android.os.Bundle
import android.os.SystemClock
import android.graphics.Rect
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.marat.albion.data.db.GrammarTaskEntity
import com.marat.albion.data.grammar.GrammarQuizResult
import com.marat.albion.data.grammar.GrammarRepository
import com.marat.albion.data.grammar.GrammarReview
import com.marat.albion.ui.games.Game
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Проверяет реальную композицию, декораторы стеков и сохранение состояния Activity. */
@RunWith(AndroidJUnit4::class)
class NavigationUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun irregularGiveUpRevealsBothFormsAndKeepsEqualActionButtons(): Unit = runBlocking {
        val c = (instrumentation.targetContext.applicationContext as AlbionApp).container
        c.importer.importIfNeeded()
        val before = c.study.observeStats().first().games.firstOrNull { it.game == Game.IRREGULAR.name }?.answers ?: 0
        ActivityScenario.launch(MainActivity::class.java).use {
            click(awaitNode { it.text?.toString() == "Практика" || it.contentDescription?.toString() == "Практика" })
            // Плитка упражнения, а не строка накопленных ошибок с тем же названием (она ведёт в режим «только ошибки»).
            click(scrollToNode { node ->
                node.text?.toString() == "Неправильные глаголы" && node.parent?.let { p ->
                    (0 until p.childCount).any { p.getChild(it)?.text?.toString() == "Впишите V2 и V3" }
                } == true
            })
            dismissFeatureHelp()
            click(scrollToNode { it.text?.toString() == "Начать" })
            val check = clickableBounds(awaitNode { it.text?.toString() == "Проверить" })
            val skip = clickableBounds(awaitNode { it.text?.toString() == "Не знаю" })
            assertEquals(check.width(), skip.width())
            assertEquals(check.height(), skip.height())
            assertEquals(check.top, skip.top)
            assertEquals(check.bottom, skip.bottom)
            clickText("Не знаю")
            awaitNode { it.text?.toString() == "Неверно" }
            clickText("Дальше")
            awaitNode { it.text?.toString()?.startsWith("2 / ") == true }
            awaitNode { it.text?.toString() == "Не знаю" }
            clickDescription("Закрыть")
            clickText("Прервать")
            clickDescription("Назад")
            click(awaitNode { it.text?.toString() == "Прогресс" || it.contentDescription?.toString() == "Прогресс" })
            val saved = withTimeout(5_000) {
                c.study.observeStats().first { s -> s.games.any { g -> g.game == Game.IRREGULAR.name && g.answers == before + 1 } }
            }.games.first { it.game == Game.IRREGULAR.name }
            scrollToNode { it.text?.toString() == "Неправильные глаголы" }
            scrollToNode { it.text?.toString() == "${saved.correct} из ${saved.answers} верно · ${(saved.accuracy!! * 100).toInt()}% · ошибок: ${saved.wrong}" }
        }
    }

    private fun clickableBounds(node: AccessibilityNodeInfo): Rect {
        var target = node
        while (!target.isClickable) target = target.parent ?: error("No clickable ancestor")
        return Rect().also(target::getBoundsInScreen)
    }

    @Test fun searchSurvivesTabSwitchAndSettingsRoundTrip() {
        ActivityScenario.launch(MainActivity::class.java).use {
            clickText("Учебник")
            setSearch("articles")
            clickText("Чтение")
            dismissFeatureHelp()
            clickText("Учебник")
            awaitNode { it.isEditable && it.text?.toString() == "articles" }
            clickDescription("Настройки")
            awaitNode { it.text?.toString() == "Занятия и цель" }
            clickDescription("Назад")
            awaitNode { it.isEditable && it.text?.toString() == "articles" }
        }
    }

    @Test fun inactiveTabStateSurvivesActivityRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            clickText("Учебник")
            setSearch("present")
            clickText("Прогресс")
            awaitNode { it.text?.toString() == "Прогресс" && it.isHeading }
            scenario.recreate()
            awaitNode { it.text?.toString() == "Прогресс" && it.isHeading }
            clickText("Учебник")
            awaitNode { it.isEditable && it.text?.toString() == "present" }
        }
    }

    @Test fun lessonSurvivesSwitchAndReselectReturnsToSectionRoot() {
        ActivityScenario.launch(MainActivity::class.java).use {
            clickText("Учебник")
            click(awaitNode { it.text?.toString() in setOf("Читать урок", "Сначала открыть правило") })
            awaitNode { it.contentDescription?.toString() == "Размер текста" }
            clickText("Чтение")
            dismissFeatureHelp()
            clickText("Учебник")
            awaitNode { it.contentDescription?.toString() == "Размер текста" }
            clickText("Учебник")
            awaitNode { it.isEditable }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitNode { it.text?.toString() == "Занятие на сегодня" }
            awaitNode { it.text?.toString()?.equals("Начать") == true }
        }
    }

    @Test fun reviewQueueCompletesTestAndRuleRoundTripKeepsTheAnswer(): Unit = runBlocking {
        val context = instrumentation.targetContext
        val c = (context.applicationContext as AlbionApp).container
        c.importer.importIfNeeded()
        val topic = c.grammar.book().lessons.first { it.number == 142 }
        // В курсе есть настоящий банк темы; на время сценария используем два детерминированных задания.
        val bundledTasks = c.database.grammarTasks().topicPool(142)
        assertTrue(bundledTasks.size >= 12)
        val previous = c.database.grammar().get(topic.id)
        val lastTopic = c.settings.settings.first().lastGrammarTopic
        val tasks = (1..2).map { n ->
            GrammarTaskEntity("review-ui-$n", -n, "C1", 142, topic.title, "choice",
                "Review check $n: ___ runs.", "He\nThey\nI", "He", "He takes a singular verb.")
        }
        try {
            c.database.grammarTasks().delete(bundledTasks.map { it.id })
            c.database.grammarTasks().upsert(tasks)
            GrammarRepository(context.assets, c.database, now = { System.currentTimeMillis() - 2 * 86_400_000L })
                .recordQuiz(topic.id, GrammarQuizResult(2, 2, true))
            assertTrue(GrammarReview.scheduled(c.grammar.book(), c.grammar.observeProgress().first().associateBy { it.topicId },
                c.grammar.observeQuizTopics().first().toSet()).any { it.topic.id == topic.id && it.dueAt < System.currentTimeMillis() })
            ActivityScenario.launch(MainActivity::class.java).use {
                clickText("Учебник")
                activate(scrollToNode { it.text?.toString()?.startsWith("Очередь (") == true })
                awaitNode { it.text?.toString()?.startsWith("К повторению:", ignoreCase = true) == true }
                setSearch("142")
                // После ввода запроса под темой появляется фрагмент правила и кнопка сдвигается вниз:
                // нажимаем через специальные возможности, а не по координатам, которые могли устареть.
                activate(scrollToNode { it.text?.toString() == "Повторить тему 142" })
                clickText("He")
                click(scrollToNode { it.text?.toString() == "Правило · тема 142" })
                awaitNode { it.contentDescription?.toString() == "Размер текста" }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                instrumentation.waitForIdleSync()
                SystemClock.sleep(650) // Закончить переход назад, прежде чем нажимать кнопку под уходящим экраном.
                // Если попытка или ответ потерялись, кнопка «Дальше» здесь не появится.
                activate(awaitNode { it.text?.toString() == "Дальше" })
                awaitNode { it.text?.toString() == "2 / 2" }
                SystemClock.sleep(500) // Даём завершиться анимации смены вопроса с таким же вариантом «He».
                clickText("He")
                clickText("Дальше")
                awaitNode { it.text?.toString() == "Безупречно." }
                val saved = try {
                    withTimeout(10_000) {
                        c.grammar.observeProgress(topic.id).first { it?.reviewStage == 2 && it.quizCorrect == 2 }
                    }!!
                } catch (e: Exception) {
                    error("Review was not saved: ${c.database.grammar().get(topic.id)}. UI: ${instrumentation.uiAutomation.rootInActiveWindow?.let(::visibleText)}")
                }
                assertEquals(2, saved.quizTotal)
                assertTrue(saved.reviewAt!! > System.currentTimeMillis())
                click(scrollToNode { it.text?.toString() == "Готово" })
                // Запрос «142» остаётся в поиске: сначала очищаем его, затем очередь пуста.
                setSearch("")
                awaitNode { it.text?.toString() == "Все повторения выполнены" }
            }
        } finally {
            c.database.grammarTasks().delete(tasks.map { it.id })
            c.database.grammarTasks().upsert(bundledTasks)
            tasks.forEach { task -> c.database.openHelper.writableDatabase.execSQL("DELETE FROM grammar_task_stats WHERE task_id = ?", arrayOf(task.id)) }
            if (previous != null) c.database.grammar().upsert(previous)
            else c.database.openHelper.writableDatabase.execSQL("DELETE FROM grammar_progress WHERE topic_id = ?", arrayOf(topic.id))
            c.settings.setLastGrammarTopic(lastTopic.orEmpty())
        }
    }

    private fun scrollToNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        repeat(20) {
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: error("No active window")
            find(root, predicate)?.let { return it }
            val bounds = Rect().also(root::getBoundsInScreen)
            val downTime = SystemClock.uptimeMillis()
            for (step in 0..15) {
                val action = if (step == 0) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE
                val y = bounds.top + bounds.height() * (0.8f - 0.25f * step / 15)
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), y, 0)
                instrumentation.sendPointerSync(event)
                event.recycle()
                SystemClock.sleep(16)
            }
            // Отпускаем палец после паузы: инерционная прокрутка сдвигает найденную кнопку перед нажатием.
            SystemClock.sleep(200)
            val up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
                bounds.exactCenterX(), bounds.top + bounds.height() * 0.55f, 0)
            instrumentation.sendPointerSync(up)
            up.recycle()
            instrumentation.waitForIdleSync()
            SystemClock.sleep(400)
        }
        error("Expected UI node was not found after scrolling. UI: ${instrumentation.uiAutomation.rootInActiveWindow?.let(::visibleText)}")
    }

    private fun visibleText(node: AccessibilityNodeInfo): String = buildString {
        if (node.isVisibleToUser && node.text != null) append(node.text).append(" | ")
        for (i in 0 until node.childCount) node.getChild(i)?.let { append(visibleText(it)) }
    }

    private fun activate(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        assertTrue("Node has no click action", target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(650)
    }

    private fun setSearch(query: String) {
        val field = awaitNode { it.isEditable }
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
        }))
        awaitNode { it.isEditable && it.text?.toString() == query }
    }

    private fun dismissFeatureHelp() {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return
        if (find(root) { it.text?.toString() in setOf("Как читать", "Как заниматься") } != null) {
            clickText("Понятно")
        }
    }

    private fun clickText(text: String) = click(awaitNode { it.text?.toString() == text })
    private fun clickDescription(text: String) = click(awaitNode { it.contentDescription?.toString() == text })

    private fun click(node: AccessibilityNodeInfo) {
        val bounds = Rect().also(node::getBoundsInScreen)
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0)
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
        instrumentation.waitForIdleSync()
        // NavDisplay завершает переход после того, как главный поток уже освободился.
        SystemClock.sleep(650)
    }

    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.uiAutomation.rootInActiveWindow?.let { root ->
                find(root, predicate)?.let { return it }
            }
            SystemClock.sleep(100)
        }
        error("Expected UI node was not found within 30 seconds. UI: ${instrumentation.uiAutomation.rootInActiveWindow?.let(::visibleText)}")
    }

    private fun find(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node.isVisibleToUser && predicate(node)) return node
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { find(it, predicate)?.let { found -> return found } }
        }
        return null
    }
}
