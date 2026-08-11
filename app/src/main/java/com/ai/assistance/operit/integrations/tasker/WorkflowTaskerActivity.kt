package com.ai.assistance.operit.integrations.tasker

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.application.OperitApplication
import com.ai.assistance.operit.core.workflow.WorkflowAuthTokenManager
import com.ai.assistance.operit.core.workflow.WorkflowIntentSecurity
import com.ai.assistance.operit.data.repository.WorkflowRepository
import com.joaomgcd.taskerpluginlibrary.SimpleResult
import com.joaomgcd.taskerpluginlibrary.SimpleResultError
import com.joaomgcd.taskerpluginlibrary.SimpleResultSuccess
import com.joaomgcd.taskerpluginlibrary.action.TaskerPluginRunnerAction
import com.joaomgcd.taskerpluginlibrary.config.TaskerPluginConfig
import com.joaomgcd.taskerpluginlibrary.config.TaskerPluginConfigHelper
import com.joaomgcd.taskerpluginlibrary.input.TaskerInput
import com.joaomgcd.taskerpluginlibrary.input.TaskerInputField
import com.joaomgcd.taskerpluginlibrary.input.TaskerInputRoot
import com.joaomgcd.taskerpluginlibrary.runner.TaskerPluginResult
import com.joaomgcd.taskerpluginlibrary.runner.TaskerPluginResultError
import com.joaomgcd.taskerpluginlibrary.runner.TaskerPluginResultSucess
import kotlinx.coroutines.runBlocking
import java.util.ArrayList

internal fun taskerWorkflowDataAccessAllowed(mainDataAccessAllowed: Boolean): Boolean =
    mainDataAccessAllowed

/**
 * Tasker Plugin Activity for triggering workflows
 * 
 * This allows Tasker to trigger Operit workflows as part of Tasker tasks.
 */
class WorkflowTaskerActivityConfig : Activity(), TaskerPluginConfig<WorkflowTaskerInput> {

    override val context: Context get() = applicationContext

    private val taskerHelper by lazy {
        WorkflowTaskerConfigHelper(this)
    }
    private val authTokenManager by lazy { WorkflowAuthTokenManager(this) }
    private var restoredCommand: String = ""
    private var restoredAuthToken: String = ""
    private var commandInput: EditText? = null
    private var authTokenInput: EditText? = null

    override val inputForTasker: TaskerInput<WorkflowTaskerInput>
        get() = TaskerInput(
            WorkflowTaskerInput(
                command = commandInput?.text?.toString()?.trim() ?: restoredCommand,
                authToken = authTokenInput?.text?.toString()?.trim() ?: restoredAuthToken,
                params = arrayListOf()
            )
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        taskerHelper.onCreate()
        setContentView(createConfigView())
    }

    override fun assignFromInput(input: TaskerInput<WorkflowTaskerInput>) {
        restoredCommand = input.regular.command
            ?: input.regular.params?.getOrNull(0)
            ?: ""
        restoredAuthToken = input.regular.authToken
            ?: input.regular.params?.getOrNull(1)
            ?: ""
        commandInput?.setText(restoredCommand)
        authTokenInput?.setText(restoredAuthToken)
    }

    private fun createConfigView(): ScrollView {
        val spacing = (resources.displayMetrics.density * 16).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(spacing, spacing, spacing, spacing)
        }

        content.addView(TextView(this).apply {
            text = getString(R.string.workflow_tasker_config_title)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            text = getString(R.string.workflow_tasker_config_help)
            setPadding(0, spacing / 2, 0, spacing)
        })

        commandInput = EditText(this).apply {
            hint = getString(R.string.workflow_tasker_config_command_hint)
            setSingleLine(true)
            setText(restoredCommand)
        }
        content.addView(
            commandInput,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        authTokenInput = EditText(this).apply {
            hint = getString(R.string.workflow_tasker_config_auth_token_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setSingleLine(true)
            setText(restoredAuthToken)
        }
        content.addView(
            authTokenInput,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = spacing / 2 }
        )

        content.addView(TextView(this).apply {
            text = getString(R.string.workflow_tasker_config_sensitive_warning)
            setPadding(0, spacing / 2, 0, spacing)
        })

        content.addView(Button(this).apply {
            text = getString(R.string.workflow_tasker_config_save)
            setOnClickListener { saveForTasker() }
        })

        return ScrollView(this).apply { addView(content) }
    }

    private fun saveForTasker() {
        val command = commandInput?.text?.toString()?.trim().orEmpty()
        val authToken = authTokenInput?.text?.toString()?.trim().orEmpty()
        var valid = true

        if (command.isBlank()) {
            commandInput?.error = getString(R.string.workflow_tasker_config_command_required)
            valid = false
        }
        if (!authTokenManager.isAuthenticAuthToken(authToken)) {
            authTokenInput?.error = getString(R.string.workflow_tasker_config_auth_token_invalid)
            valid = false
        }
        if (valid) {
            taskerHelper.finishForTasker()
        }
    }
}

/**
 * Tasker Plugin Config Helper
 */
class WorkflowTaskerConfigHelper(config: TaskerPluginConfig<WorkflowTaskerInput>) : 
    TaskerPluginConfigHelper<WorkflowTaskerInput, Unit, WorkflowTaskerRunner>(config) {

    override val inputClass: Class<WorkflowTaskerInput>
        get() = WorkflowTaskerInput::class.java
    
    override val outputClass: Class<Unit>
        get() = Unit::class.java
    
    override val runnerClass: Class<WorkflowTaskerRunner>
        get() = WorkflowTaskerRunner::class.java

    // The library's default blurb includes every annotated input. Never expose auth_token there.
    override val addDefaultStringBlurb: Boolean
        get() = false

    override fun addToStringBlurb(
        input: TaskerInput<WorkflowTaskerInput>,
        blurbBuilder: StringBuilder
    ) {
        val command = input.regular.command ?: input.regular.params?.getOrNull(0)
        if (!command.isNullOrBlank()) {
            blurbBuilder.append("Command: ").append(command)
        }
    }

    override fun isInputValid(input: TaskerInput<WorkflowTaskerInput>): SimpleResult {
        val command = input.regular.command ?: input.regular.params?.getOrNull(0)
        val authToken = input.regular.authToken ?: input.regular.params?.getOrNull(1)
        return if (command.isNullOrBlank() || !WorkflowIntentSecurity.isValidAuthToken(authToken)) {
            SimpleResultError("Workflow command and auth_token are required")
        } else {
            SimpleResultSuccess()
        }
    }
}

/**
 * Tasker Plugin Input Data
 */
@TaskerInputRoot
class WorkflowTaskerInput @JvmOverloads constructor(
    @field:TaskerInputField("command")
    var command: String? = null,
    @field:TaskerInputField("auth_token")
    var authToken: String? = null,
    // Kept only so existing serialized Tasker configurations can fail closed and be updated.
    var params: ArrayList<String>? = arrayListOf()
)

/**
 * Tasker Plugin Runner
 */
class WorkflowTaskerRunner : TaskerPluginRunnerAction<WorkflowTaskerInput, Unit>() {
    
    override fun run(
        context: Context,
        input: TaskerInput<WorkflowTaskerInput>
    ): TaskerPluginResult<Unit> {
        if (!taskerWorkflowDataAccessAllowed(OperitApplication.isMainDataAccessAllowed(context))) {
            return TaskerPluginResultError(
                IllegalStateException("Workflow data is unavailable while migration or restore is in progress")
            )
        }
        val legacyParams = input.regular.params
        val command = input.regular.command ?: legacyParams?.getOrNull(0)
        val authToken = input.regular.authToken ?: legacyParams?.getOrNull(1)

        if (command.isNullOrBlank() || !WorkflowIntentSecurity.isValidAuthToken(authToken)) {
            return TaskerPluginResultError(
                IllegalArgumentException("Workflow command and auth_token are required")
            )
        }
        val authTokenManager = WorkflowAuthTokenManager(context)
        if (!authTokenManager.isAuthenticAuthToken(authToken)) {
            return TaskerPluginResultError(
                IllegalArgumentException("Workflow auth_token is not authentic for this installation")
            )
        }
        return try {
            val repository = WorkflowRepository(context)
            runBlocking {
                repository.triggerWorkflowsByTaskerEvent(command, authToken).getOrThrow()
            }
            TaskerPluginResultSucess()
        } catch (e: Exception) {
            TaskerPluginResultError(e) // Return error to Tasker for debugging
        }
    }
}

