package com.sol.sillytavern.native.vm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Installs a test main dispatcher so `viewModelScope` can run on the JVM.
 *
 * [UnconfinedTestDispatcher] runs a launched coroutine eagerly up to its first
 * suspension, which is what makes the assertions right after the constructor
 * deterministic for the non-suspending fakes used here.
 */
class MainDispatcherRule(
    private val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun finished(description: Description) = Dispatchers.resetMain()
}
