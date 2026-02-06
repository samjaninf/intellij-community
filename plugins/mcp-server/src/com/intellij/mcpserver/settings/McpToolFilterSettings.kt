package com.intellij.mcpserver.settings

import com.intellij.openapi.components.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Service
@State(name = "McpToolFilterSettings", storages = [Storage("mcpToolFilter.xml")])
internal class McpToolFilterSettings : SimplePersistentStateComponent<McpToolFilterSettings.MyState>(MyState()) {
  companion object {
    @JvmStatic
    fun getInstance(): McpToolFilterSettings = service()

    const val DEFAULT_FILTER: String = "*"
  }

  private val _toolsFilterFlow = MutableStateFlow(state.toolsFilter ?: DEFAULT_FILTER)

  val toolsFilterFlow: StateFlow<String>
    get() = _toolsFilterFlow.asStateFlow()

  override fun loadState(state: MyState) {
    super.loadState(state)
    _toolsFilterFlow.value = state.toolsFilter ?: DEFAULT_FILTER
  }

  var toolsFilter: String
    get() = state.toolsFilter ?: DEFAULT_FILTER
    set(value) {
      state.toolsFilter = value
      _toolsFilterFlow.value = value
    }

  internal class MyState : BaseState() {
    var toolsFilter: String? by string(DEFAULT_FILTER)
  }
}
