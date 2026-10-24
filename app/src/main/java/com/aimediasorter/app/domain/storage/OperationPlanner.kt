package com.aimediasorter.app.domain.storage

import com.aimediasorter.app.data.model.TransferMethod

data class PlannedDestination(val mediaId: Long,val categoryId: String,val method: TransferMethod,val sequence: Int)
object OperationPlanner {
    /** Copies precede the one explicitly chosen move; no source can be moved twice. */
    fun build(mediaId: Long,categories: Set<String>,method: TransferMethod,moveCategory: String?): List<PlannedDestination> {
        require(categories.isNotEmpty() && categories.size<=50)
        if(method==TransferMethod.COPY) return categories.sorted().mapIndexed { i,id -> PlannedDestination(mediaId,id,TransferMethod.COPY,i) }
        require(moveCategory in categories) { "Choose the destination receiving the moved original." }
        return categories.filter { it!=moveCategory }.sorted().mapIndexed { i,id -> PlannedDestination(mediaId,id,TransferMethod.COPY,i) } +
            PlannedDestination(mediaId,requireNotNull(moveCategory),TransferMethod.MOVE,categories.size-1)
    }
}
