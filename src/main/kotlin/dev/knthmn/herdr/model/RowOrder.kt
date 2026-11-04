package dev.knthmn.herdr.model

/** The order of live rows for a [SortOrder]. */
object RowOrder {
    /** Status order: rows that need attention first. */
    private val STATUS =
        listOf(
            AgentState.BLOCKED,
            AgentState.ERROR,
            AgentState.UNREAD,
            AgentState.RUNNING,
            AgentState.IDLE,
            AgentState.NONE,
            AgentState.FINISHED,
        )

    private val recent = compareByDescending<Row> { it.sinceMs ?: 0 }

    fun of(sort: SortOrder): Comparator<Row> =
        when (sort) {
            SortOrder.RECENT -> recent
            SortOrder.STATUS -> compareBy<Row> { STATUS.indexOf(it.state) }.then(recent)
        }
}
