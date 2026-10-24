package com.aimediasorter.app.ui.screens
import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aimediasorter.app.R
import com.aimediasorter.app.domain.cloud.*
import java.math.BigDecimal

data class CloudUiState(val hasKey: Boolean=false,val models: List<String> = emptyList(),
    val usage: String="0 / 0 / 0",val busy: Boolean=false,val error: String?=null)

@Composable fun CloudSettingsCard(o: CloudOptions,state: CloudUiState,onOptions: (CloudOptions)->Unit,
    onSaveKey: (String)->Unit,onClearKey: ()->Unit,onRefreshModels: ()->Unit,onRefreshState: ()->Unit) {
    var key by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var invalid by remember { mutableStateOf(false) }
    var maxRequests by remember(o.maxRequests) { mutableStateOf(o.maxRequests.toString()) }
    var daily by remember(o.maxDailyRequests) { mutableStateOf(o.maxDailyRequests.toString()) }
    var tokens by remember(o.maxOutputTokens) { mutableStateOf(o.maxOutputTokens.toString()) }
    var retries by remember(o.retries) { mutableStateOf(o.retries.toString()) }
    fun dollars(micros: Long)=BigDecimal.valueOf(micros,6).stripTrailingZeros().toPlainString()
    var perAttempt by remember(o.estimatedMicrosPerAttempt) { mutableStateOf(dollars(o.estimatedMicrosPerAttempt)) }
    var batch by remember(o.maxEstimatedBatchMicros) { mutableStateOf(dollars(o.maxEstimatedBatchMicros)) }
    val activity=LocalContext.current as? Activity
    DisposableEffect(activity,key.isNotEmpty()) {
        val window=activity?.window
        val hadSecure=window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE)!=0
        if(key.isNotEmpty()) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if(!hadSecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    LaunchedEffect(Unit) { onRefreshState() }
    OutlinedCard(modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.cloud_title),style=MaterialTheme.typography.titleMedium)
            AnalysisMode.entries.forEach { mode ->
                val label=when(mode) { AnalysisMode.OFFLINE->R.string.cloud_offline;AnalysisMode.GEMINI->R.string.cloud_gemini;AnalysisMode.HYBRID->R.string.cloud_hybrid }
                Row {
                    RadioButton(o.mode==mode,onClick={onOptions(o.copy(mode=mode))})
                    Text(stringResource(label),Modifier.padding(top=12.dp))
                }
            }
            Text(stringResource(R.string.cloud_privacy))
            OutlinedTextField(key,{key=it},label={Text(stringResource(R.string.cloud_key))},
                visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),
                singleLine=true,modifier=Modifier.fillMaxWidth())
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(enabled=Regex("[A-Za-z0-9_-]{20,256}").matches(key),onClick={onSaveKey(key);key=""}) { Text(stringResource(R.string.cloud_save_key)) }
                OutlinedButton(enabled=state.hasKey,onClick={key="";onClearKey()}) { Text(stringResource(R.string.cloud_clear_key)) }
            }
            Text(stringResource(if(state.hasKey) R.string.cloud_key_saved else R.string.cloud_key_missing))
            OutlinedButton(enabled=state.hasKey&&!state.busy,onClick=onRefreshModels) { Text(stringResource(R.string.cloud_refresh_models)) }
            if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Box {
                OutlinedButton(onClick={menu=true},enabled=state.models.isNotEmpty()) {
                    Text(o.model.ifBlank { stringResource(R.string.cloud_select_model) })
                }
                DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                    state.models.forEach { name -> DropdownMenuItem(text={Text(name)},onClick={menu=false;onOptions(o.copy(model=name))}) }
                }
            }
            Text(stringResource(R.string.cloud_model_note),style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(maxRequests,{maxRequests=it},label={Text(stringResource(R.string.cloud_max_requests))},singleLine=true)
            OutlinedTextField(daily,{daily=it},label={Text(stringResource(R.string.cloud_daily_requests))},singleLine=true)
            OutlinedTextField(tokens,{tokens=it},label={Text(stringResource(R.string.cloud_output_tokens))},singleLine=true)
            OutlinedTextField(retries,{retries=it},label={Text(stringResource(R.string.cloud_retries))},singleLine=true)
            OutlinedTextField(perAttempt,{perAttempt=it},label={Text(stringResource(R.string.cloud_per_attempt))},singleLine=true)
            OutlinedTextField(batch,{batch=it},label={Text(stringResource(R.string.cloud_batch_budget))},singleLine=true)
            Text(stringResource(R.string.cloud_budget_note),style=MaterialTheme.typography.bodySmall)
            Button(onClick={
                try {
                    fun micros(v: String)=BigDecimal(v.replace(',','.')).movePointRight(6).longValueExact()
                    val next=o.copy(maxRequests=maxRequests.toInt(),maxDailyRequests=daily.toInt(),
                        maxOutputTokens=tokens.toInt(),retries=retries.toInt(),
                        estimatedMicrosPerAttempt=micros(perAttempt),maxEstimatedBatchMicros=micros(batch))
                    next.validate();onOptions(next);invalid=false
                } catch(_: Exception) { invalid=true }
            }) { Text(stringResource(R.string.cloud_save_limits)) }
            if(invalid) Text(stringResource(R.string.cloud_invalid_limits),color=MaterialTheme.colorScheme.error)
            if(state.error!=null) Text(state.error,color=MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.cloud_usage,state.usage))
            TextButton(onClick=onRefreshState) { Text(stringResource(R.string.cloud_refresh_usage)) }
        }
    }
}
@Composable fun CloudConsentDialog(options: CloudOptions,hasKey: Boolean,onConfirm: ()->Unit,onDismiss: ()->Unit) {
    var accepted by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest=onDismiss,title={Text(stringResource(R.string.cloud_consent_title))},
        text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.cloud_consent_body,options.maxRequests,options.model))
            Text(stringResource(R.string.cloud_budget_note))
            Row { Checkbox(accepted,{accepted=it});Text(stringResource(R.string.cloud_consent_checkbox)) }
            if(!hasKey||!options.ready()) Text(stringResource(R.string.cloud_setup_required),color=MaterialTheme.colorScheme.error)
        }},
        confirmButton={TextButton(enabled=accepted&&hasKey&&options.ready(),onClick=onConfirm) { Text(stringResource(R.string.cloud_consent_send)) }},
        dismissButton={TextButton(onClick=onDismiss) { Text(stringResource(R.string.cloud_consent_cancel)) }})
}
