package com.aimediasorter.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.aimediasorter.app.R
import com.aimediasorter.app.data.local.*
import com.aimediasorter.app.ui.theme.*

@Composable fun analysisStatusLabel(status: String)=stringResource(when(status) {
    "FAILED" -> R.string.virtual_failed
    "NEEDS_REVIEW","LEGACY" -> R.string.virtual_review
    "APPROVED" -> R.string.virtual_approved
    "PROPOSED" -> R.string.virtual_proposed
    else -> R.string.virtual_pending
})
@Composable fun MediaThumbnail(item: MediaItemEntity,modifier: Modifier=Modifier) {
    val context=LocalContext.current
    val request=remember(item.uriString,item.destinationUriString,item.sha256Checksum) {
        ImageRequest.Builder(context).data(item.destinationUriString ?: item.uriString)
            .size(320,320).videoFrameMillis(1000)
            .memoryCacheKey((item.destinationUriString ?: item.uriString)+":"+item.sha256Checksum+":thumb320")
            .crossfade(false).build()
    }
    var failed by remember(request) { mutableStateOf(false) }
    Box(modifier.background(AmoledElevatedSurface)) {
        AsyncImage(request,contentDescription=item.fileName,contentScale=ContentScale.Crop,
            onError={failed=true},onSuccess={failed=false},modifier=Modifier.fillMaxSize())
        if(failed) Text(stringResource(R.string.virtual_preview_unavailable),Modifier.padding(8.dp),style=MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable fun VirtualGalleryScreen(
    media: List<MediaItemEntity>,categories: List<CategoryEntity>,links: List<MediaCategoryLink>,
    initialFolder: String="",onInspect: (Long)->Unit,onCorrect: (Set<Long>,Set<String>,Boolean)->Unit,
    onReanalyze: (Set<Long>)->Unit,onOrganize: (Set<Long>)->Unit,onAnalyze: ()->Unit,onAdvanced: ()->Unit
) {
    var folder by rememberSaveable(initialFolder) { mutableStateOf(initialFolder) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var correction by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf(setOf<String>()) }
    val currentIds=remember(media) { media.map { it.id }.toSet() }
    val membership=remember(links,currentIds) {
        links.filter { it.origin=="EFFECTIVE" && it.mediaId in currentIds }.groupBy { it.categoryId }.mapValues { (_,v)->v.map { it.mediaId }.toSet() }
    }
    val byId=remember(media) { media.associateBy { it.id } }
    val review=remember(media) { media.filter { it.needsManualReview && it.analysisStatus!="FAILED" } }
    val failed=remember(media) { media.filter { it.analysisStatus=="FAILED" } }
    val pending=remember(media) { media.filter { it.analysisStatus=="PENDING" } }
    val visible=remember(media,folder,membership) { when(folder) {
        "__review__" -> review
        "__failed__" -> failed
        "__pending__" -> pending
        "__all__" -> media
        else -> membership[folder].orEmpty().mapNotNull { byId[it] }.sortedByDescending { it.originalLastModifiedMs }
    } }
    val title=when(folder) {
        "__review__" -> stringResource(R.string.virtual_review)
        "__failed__" -> stringResource(R.string.virtual_failed)
        "__pending__" -> stringResource(R.string.virtual_pending)
        "__all__" -> stringResource(R.string.virtual_all)
        "" -> stringResource(R.string.virtual_folders)
        else -> categories.firstOrNull { it.id==folder }?.let { categoryDisplayName(it.id,it.name) }.orEmpty()
    }
    BackHandler(folder.isNotEmpty() && initialFolder.isEmpty()) { folder="";selected=emptySet() }
    LaunchedEffect(currentIds) { selected=selected.intersect(currentIds) }
    Column(Modifier.fillMaxSize().background(AmoledPureBlack).padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(title,style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
            if(folder.isNotEmpty() && initialFolder.isEmpty()) TextButton(onClick={folder="";selected=emptySet()}) { Text(stringResource(R.string.btn_back)) }
        }
        Text(stringResource(R.string.virtual_only_notice),style=MaterialTheme.typography.bodySmall)
        if(folder.isEmpty()) {
            val cards=categories.map { it.id }+listOf("__review__","__failed__","__pending__","__all__")
            LazyVerticalGrid(GridCells.Adaptive(150.dp),verticalArrangement=Arrangement.spacedBy(10.dp),
                horizontalArrangement=Arrangement.spacedBy(10.dp),modifier=Modifier.weight(1f)) {
                items(cards,key={it}) { id ->
                    val items=when(id) {
                        "__review__"->review;"__failed__"->failed;"__pending__"->pending;"__all__"->media
                        else->membership[id].orEmpty().mapNotNull { byId[it] }
                    }
                    val label=when(id) {
                        "__review__"->stringResource(R.string.virtual_review)
                        "__failed__"->stringResource(R.string.virtual_failed)
                        "__pending__"->stringResource(R.string.virtual_pending)
                        "__all__"->stringResource(R.string.virtual_all)
                        else->categories.first { it.id==id }.let { categoryDisplayName(it.id,it.name) }
                    }
                    Surface(shape=RoundedCornerShape(12.dp),border=BorderStroke(1.dp,AmoledSubtleBorder),
                        color=AmoledPureBlack,modifier=Modifier.clickable { folder=id;selected=emptySet() }) {
                        Column {
                            items.firstOrNull()?.let { MediaThumbnail(it,Modifier.fillMaxWidth().height(112.dp)) }
                            Text(label,Modifier.padding(8.dp),style=MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.virtual_count,items.size,items.count { it.needsManualReview }),
                                Modifier.padding(horizontal=8.dp).padding(bottom=8.dp),style=MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            TextButton(onClick=onAdvanced) { Text(stringResource(R.string.virtual_advanced)) }
            if(media.isEmpty()) Button(onClick=onAnalyze) { Text(stringResource(R.string.nav_analyze)) }
        } else {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton(onClick={selected=if(selected.isEmpty()) visible.take(100).map { it.id }.toSet() else emptySet()}) {
                    Text(stringResource(if(selected.isEmpty()) R.string.virtual_select else R.string.virtual_clear_selection))
                }
                Text(stringResource(R.string.virtual_selected,selected.size),Modifier.padding(top=12.dp))
            }
            if(selected.isNotEmpty()) {
                Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick={chosen=emptySet();correction=true},modifier=Modifier.weight(1f)) { Text(stringResource(R.string.virtual_correct)) }
                    OutlinedButton(onClick={onReanalyze(selected)},modifier=Modifier.weight(1f)) { Text(stringResource(R.string.virtual_reanalyze)) }
                }
                OutlinedButton(onClick={onOrganize(selected)},modifier=Modifier.fillMaxWidth()) { Text(stringResource(R.string.virtual_plan)) }
            }
            if(visible.isEmpty()) Text(stringResource(R.string.virtual_empty))
            LazyVerticalGrid(GridCells.Adaptive(112.dp),verticalArrangement=Arrangement.spacedBy(6.dp),
                horizontalArrangement=Arrangement.spacedBy(6.dp),modifier=Modifier.weight(1f)) {
                items(visible,key={it.id}) { item ->
                    Surface(color=AmoledPureBlack,border=BorderStroke(if(item.id in selected) 2.dp else 1.dp,
                        if(item.id in selected) PrimaryPurple else AmoledSubtleBorder),shape=RoundedCornerShape(8.dp),
                        modifier=Modifier.combinedClickable(
                            onClick={if(selected.isEmpty()) onInspect(item.id) else selected=if(item.id in selected) selected-item.id else (selected+item.id).take(100).toSet()},
                            onLongClick={selected=if(item.id in selected) selected-item.id else (selected+item.id).take(100).toSet()})) {
                        Column {
                            MediaThumbnail(item,Modifier.fillMaxWidth().aspectRatio(1f))
                            Text(item.fileName,Modifier.padding(4.dp),maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelSmall)
                            Text(analysisStatusLabel(item.analysisStatus),Modifier.padding(4.dp),style=MaterialTheme.typography.labelSmall,
                                color=if(item.needsManualReview) StatusAmber else PrimaryPurpleLight)
                            Text(membership.filterValues { item.id in it }.keys.mapNotNull { id->categories.firstOrNull { it.id==id } }
                                .joinToString { it.name },Modifier.padding(4.dp),maxLines=2,style=MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
    if(correction) AlertDialog(onDismissRequest={correction=false},title={Text(stringResource(R.string.virtual_correct))},
        text={Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.virtual_replace_categories))
            categories.filter { it.isEnabled }.forEach { cat ->
                Row { Checkbox(cat.id in chosen,{chosen=if(it) chosen+cat.id else chosen-cat.id});Text(categoryDisplayName(cat.id,cat.name),Modifier.padding(top=12.dp)) }
            }
        }},
        confirmButton={TextButton(enabled=chosen.isNotEmpty(),onClick={onCorrect(selected,chosen,false);correction=false;selected=emptySet()}) { Text(stringResource(R.string.virtual_save_categories)) }},
        dismissButton={TextButton(onClick={correction=false}) { Text(stringResource(R.string.btn_cancel)) }})
}
