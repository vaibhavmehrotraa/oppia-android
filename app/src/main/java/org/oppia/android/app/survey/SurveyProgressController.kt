package org.oppia.android.app.survey

import java.util.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.oppia.android.app.model.EphemeralQuestion
import org.oppia.android.app.model.EphemeralSurveyQuestion
import org.oppia.android.app.model.SurveyQuestion
import org.oppia.android.app.model.SurveySelectedAnswer
import org.oppia.android.util.data.AsyncResult
import org.oppia.android.util.data.DataProvider
import org.oppia.android.util.data.DataProviders
import org.oppia.android.util.data.DataProviders.Companion.combineWith
import org.oppia.android.util.data.DataProviders.Companion.transformNested
import org.oppia.android.util.threading.BackgroundDispatcher

private const val BEGIN_SESSION_RESULT_PROVIDER_ID = "SurveyProgressController.begin_session_result"
private const val EMPTY_QUESTIONS_LIST_DATA_PROVIDER_ID =
  "SurveyProgressController.create_empty_questions_list_data_provider_id"
private const val MONITORED_QUESTION_LIST_PROVIDER_ID = "" +
  "SurveyProgressController.monitored_question_list"
private const val CURRENT_QUESTION_PROVIDER_ID =
  "SurveyProgressController.current_question"
private const val EPHEMERAL_QUESTION_FROM_UPDATED_QUESTION_LIST_PROVIDER_ID =
  "SurveyProgressController.ephemeral_question_from_updated_question_list"

/**
 * A default session ID to be used before a session has been initialized.
 *
 * This session ID will never match, so messages that are received with this ID will never be
 * processed.
 */
private const val DEFAULT_SESSION_ID = "default_session_id"

/**
 * Controller for tracking the non-persisted progress of a survey.
 */
@Singleton
class SurveyProgressController @Inject constructor(
  private val dataProviders: DataProviders,
  @BackgroundDispatcher private val backgroundCoroutineDispatcher: CoroutineDispatcher
) {
  private var mostRecentSessionId: String? = null
  private val activeSessionId: String
    get() = mostRecentSessionId ?: DEFAULT_SESSION_ID

  private var mostRecentEphemeralQuestionFlow =
    createAsyncResultStateFlow<EphemeralSurveyQuestion>(
      AsyncResult.Failure(IllegalStateException("Survey is not yet initialized."))
    )

  private var mostRecentCommandQueue: SendChannel<ControllerMessage<*>>? = null

  private val monitoredQuestionListDataProvider: DataProviders.NestedTransformedDataProvider<Any?> =
    createCurrentQuestionDataProvider(createEmptyQuestionsListDataProvider())

  /**
   * Begins a survey session based on a set of questions and returns a [DataProvider] indicating
   * whether the start was successful.
   */
  internal fun beginSurveySession(
    questionsListDataProvider: DataProvider<List<SurveyQuestion>>
  ): DataProvider<Any?> {
    val ephemeralQuestionFlow = createAsyncResultStateFlow<EphemeralSurveyQuestion>()
    val sessionId = UUID.randomUUID().toString().also {
      mostRecentSessionId = it
      mostRecentEphemeralQuestionFlow = ephemeralQuestionFlow
      mostRecentCommandQueue = createControllerCommandActor()
    }
    monitoredQuestionListDataProvider.setBaseDataProvider(questionsListDataProvider) {
      println("Questions for init ${it.size}")
      it.forEach {
        println("question name: ${it.questionName}")
      }
      maybeSendReceiveQuestionListEvent(mostRecentCommandQueue, it)
    }
    val beginSessionResultFlow = createAsyncResultStateFlow<Any?>()
    val initializeMessage: ControllerMessage<*> =
      ControllerMessage.InitializeController(ephemeralQuestionFlow, sessionId, beginSessionResultFlow
      )
    sendCommandForOperation(initializeMessage) {
      "Failed to schedule command for initializing the question assessment progress controller."
    }
    return beginSessionResultFlow.convertToSessionProvider(BEGIN_SESSION_RESULT_PROVIDER_ID)
  }

  fun getCurrentQuestion(): DataProvider<EphemeralSurveyQuestion> {
    val ephemeralQuestionDataProvider =
      mostRecentEphemeralQuestionFlow.convertToSessionProvider(CURRENT_QUESTION_PROVIDER_ID)

    // Combine ephemeral question with the monitored question list to ensure that changes to the
    // questions list trigger a recompute of the ephemeral question.
    return monitoredQuestionListDataProvider.combineWith(
      ephemeralQuestionDataProvider, EPHEMERAL_QUESTION_FROM_UPDATED_QUESTION_LIST_PROVIDER_ID
    ) { _, currentQuestion ->
      currentQuestion
    }
  }

  private fun createCurrentQuestionDataProvider(
    questionsListDataProvider: DataProvider<List<SurveyQuestion>>
  ): DataProviders.NestedTransformedDataProvider<Any?> {
    return questionsListDataProvider.transformNested(MONITORED_QUESTION_LIST_PROVIDER_ID) {
      maybeSendReceiveQuestionListEvent(commandQueue = null, it)
    }
  }

  /** Returns a [DataProvider] that always provides an empty list of [SurveyQuestion]s. */
  private fun createEmptyQuestionsListDataProvider(): DataProvider<List<SurveyQuestion>> {
    return dataProviders.createInMemoryDataProvider(EMPTY_QUESTIONS_LIST_DATA_PROVIDER_ID) {
      listOf()
    }
  }

  private fun createControllerCommandActor(): SendChannel<ControllerMessage<*>> {
    lateinit var controllerState: ControllerState

    @Suppress("JoinDeclarationAndAssignment") // Warning is incorrect in this case.
    lateinit var commandQueue: SendChannel<ControllerMessage<*>>
    commandQueue = CoroutineScope(
      backgroundCoroutineDispatcher
    ).actor(capacity = Channel.UNLIMITED) {
      for (message in channel) {
        when (message) {
          is ControllerMessage.InitializeController -> {
            controllerState = ControllerState(
              message.sessionId,
              message.ephemeralQuestionFlow,
              commandQueue
            ).also {
              it.beginSurveySessionImpl(message.callbackFlow)
            }
          }
          is ControllerMessage.FinishSurveySession -> TODO()
          is ControllerMessage.MoveToNextQuestion -> TODO()
          is ControllerMessage.MoveToPreviousQuestion -> TODO()
          is ControllerMessage.RecomputeQuestionAndNotify ->
            controllerState.recomputeCurrentQuestionAndNotifySync()
          is ControllerMessage.SaveFullCompletion -> TODO()
          is ControllerMessage.SavePartialCompletion -> TODO()
          is ControllerMessage.SubmitAnswer -> TODO()
          is ControllerMessage.ReceiveQuestionList -> controllerState.handleUpdatedQuestionsList(
            message.questionsList
          )
        }
      }
    }
    return commandQueue
  }

  private fun <T> sendCommandForOperation(
    message: ControllerMessage<T>,
    lazyFailureMessage: () -> String
  ) {
    // TODO(#4119): Switch this to use trySend(), instead, which is much cleaner and doesn't require
    //  catching an exception.
    val flowResult: AsyncResult<T> = try {
      val commandQueue = mostRecentCommandQueue
      when {
        commandQueue == null ->
          AsyncResult.Failure(IllegalStateException("Session isn't initialized yet."))
        !commandQueue.offer(message) ->
          AsyncResult.Failure(IllegalStateException(lazyFailureMessage()))
        // Ensure that the result is first reset since there will be a delay before the message is
        // processed (if there's a flow).
        else -> AsyncResult.Pending()
      }
    } catch (e: Exception) {
      AsyncResult.Failure(e)
    }
    // This must be assigned separately since flowResult should always be calculated, even if
    // there's no callbackFlow to report it.
    message.callbackFlow?.value = flowResult
  }

  private suspend fun maybeSendReceiveQuestionListEvent(
    commandQueue: SendChannel<ControllerMessage<*>>?,
    questionsList: List<SurveyQuestion>
  ): AsyncResult<Any?> {
    // Only send the message if there's a queue to send it to (which there might not be for cases
    // where a session isn't active).
    commandQueue?.send(ControllerMessage.ReceiveQuestionList(questionsList, activeSessionId))
    return AsyncResult.Success(null)
  }

  private suspend fun ControllerState.beginSurveySessionImpl(
    beginSessionResultFlow: MutableStateFlow<AsyncResult<Any?>>
  ) {
    tryOperation(beginSessionResultFlow) {
      recomputeCurrentQuestionAndNotifyAsync()
    }
  }

  private fun <T> createAsyncResultStateFlow(initialValue: AsyncResult<T> = AsyncResult.Pending()) =
    MutableStateFlow(initialValue)

  private fun <T> StateFlow<AsyncResult<T>>.convertToSessionProvider(
    baseId: String
  ): DataProvider<T> = dataProviders.run {
    convertAsyncToAutomaticDataProvider("${baseId}_$activeSessionId")
  }

  /**
   * Represents a message that can be sent to [mostRecentCommandQueue] to process changes to
   * [ControllerState] (since all changes must be synchronized).
   *
   * Messages are expected to be resolved serially (though their scheduling can occur across
   * multiple threads, so order cannot be guaranteed until they're enqueued).
   */
  private sealed class ControllerMessage<T> {
    /**
     * The session ID corresponding to this message (the message is expected to be ignored if it
     * doesn't correspond to an active session).
     */
    abstract val sessionId: String

    /**
     * The [DataProvider]-tied [MutableStateFlow] that represents the result of the operation
     * corresponding to this message, or ``null`` if the caller doesn't care about observing the
     * result.
     */
    abstract val callbackFlow: MutableStateFlow<AsyncResult<T>>?

    /** [ControllerMessage] for initializing a new survey session. */
    data class InitializeController(
      val ephemeralQuestionFlow: MutableStateFlow<AsyncResult<EphemeralSurveyQuestion>>,
      override val sessionId: String,
      override val callbackFlow: MutableStateFlow<AsyncResult<Any?>>
    ) : ControllerMessage<Any?>()

    /** [ControllerMessage] for ending the current survey session. */
    data class FinishSurveySession(
      override val sessionId: String,
      override val callbackFlow: MutableStateFlow<AsyncResult<Any?>>
    ) : ControllerMessage<Any?>()

    /** [ControllerMessage] for submitting a new [SurveySelectedAnswer]. */
    data class SubmitAnswer(
      val selectedAnswer: SurveySelectedAnswer,
      override val sessionId: String,
      override val callbackFlow: MutableStateFlow<AsyncResult<Any?>>
    ) : ControllerMessage<Any?>()

    /** [ControllerMessage] to move to the previous question in the survey. */
    data class MoveToPreviousQuestion(
      override val sessionId: String,
      override val callbackFlow: MutableStateFlow<AsyncResult<Any?>>
    ) : ControllerMessage<Any?>()

    /** [ControllerMessage] to move to the next question in the survey. */
    data class MoveToNextQuestion(
      override val sessionId: String,
      override val callbackFlow: MutableStateFlow<AsyncResult<Any?>>
    ) : ControllerMessage<Any?>()

    /**
     * [ControllerMessage] to indicate that the mandatory part of the survey is completed and
     * should be saved/submitted.
     * TODO: remove the comment on the next line
     * Maybe we can use this information to notify some subscriber that a survey can be submitted
     * if an exit action is triggered
     */
    data class SavePartialCompletion(
      override val sessionId: String,
      override val callbackFlow: MutableStateFlow<AsyncResult<Any?>>? = null
    ) : ControllerMessage<Any?>()

    /**
     * [ControllerMessage] to indicate that the optional part of the survey is completed and
     * should be saved/submitted.
     */
    data class SaveFullCompletion(
      override val sessionId: String,
      override val callbackFlow: MutableStateFlow<AsyncResult<Any?>>? = null
    ) : ControllerMessage<Any?>()

    /**
     * [ControllerMessage] which recomputes the current [EphemeralSurveyQuestion] and notifies
     * subscribers of the [DataProvider] returned by [getCurrentQuestion] of the change.
     * This is only used in cases where an external operation trigger changes that are only
     * reflected when recomputing the question (e.g. an answer was changed).
     */
    data class RecomputeQuestionAndNotify(
      override val sessionId: String,
      override val callbackFlow: MutableStateFlow<AsyncResult<Any?>>? = null
    ) : ControllerMessage<Any?>()

    /**
     * [ControllerMessage] for finishing the initialization of the survey session by providing a
     * list of [SurveyQuestion]s to display.
     */
    data class ReceiveQuestionList(
      val questionsList: List<SurveyQuestion>,
      override val sessionId: String,
      override val callbackFlow: MutableStateFlow<AsyncResult<Any?>>? = null
    ) : ControllerMessage<Any?>()
  }

  private suspend fun ControllerState.handleUpdatedQuestionsList(questionsList: List<SurveyQuestion>) {
    // The questions list is possibly changed which may affect the computed ephemeral question.
    if (!this.isQuestionsListInitialized || this.questionsList != questionsList) {
      this.questionsList = questionsList
      // Only notify if the questions list is different (otherwise an infinite notify loop might be
      // started).
      recomputeCurrentQuestionAndNotifySync()
    }
  }

  private suspend fun <T> ControllerState.tryOperation(
    resultFlow: MutableStateFlow<AsyncResult<T>>,
    operation: suspend ControllerState.() -> T
  ) {
    try {
      resultFlow.emit(AsyncResult.Success(operation()))
      recomputeCurrentQuestionAndNotifySync()
    } catch (e: Exception) {
      //exceptionsController.logNonFatalException(e)
      resultFlow.emit(AsyncResult.Failure(e))
    }
  }

  /**
   * Immediately recomputes the current question & notifies it's been changed.
   *
   * This should only be called when the caller can guarantee that the current [ControllerState] is
   * correct and up-to-date (i.e. that this is being called via a direct call path from the actor).
   *
   * All other cases must use [recomputeCurrentQuestionAndNotifyAsync].
   */
  private suspend fun ControllerState.recomputeCurrentQuestionAndNotifySync() {
    recomputeCurrentQuestionAndNotifyImpl()
  }

  /**
   * Sends a message to recompute the current question & notify it's been changed.
   *
   * This must be used in cases when the current [ControllerState] may no longer be up-to-date to
   * ensure state isn't leaked across training sessions.
   */
  private suspend fun ControllerState.recomputeCurrentQuestionAndNotifyAsync() {
    commandQueue.send(ControllerMessage.RecomputeQuestionAndNotify(sessionId))
  }

  private suspend fun ControllerState.recomputeCurrentQuestionAndNotifyImpl() {
    ephemeralQuestionFlow.emit(
      if (isQuestionsListInitialized) {
        // Only compute the ephemeral question if there's a questions list loaded (otherwise the
        // controller is in a pending state).
        retrieveCurrentQuestionAsync(questionsList)
      } else AsyncResult.Pending()
    )
  }

  private suspend fun ControllerState.retrieveCurrentQuestionAsync(
    questionsList: List<SurveyQuestion>
  ): AsyncResult<EphemeralSurveyQuestion> {
    return AsyncResult.Success(
      retrieveEphemeralQuestion(questionsList)
    )
  }

  private fun ControllerState.retrieveEphemeralQuestion(questionsList: List<SurveyQuestion>): EphemeralSurveyQuestion {
    return EphemeralSurveyQuestion.newBuilder()
      .setQuestion(questionsList[0])
      .build()
  }

  /**
   * Represents the current synchronized state of the controller.
   *
   * This object's instance is tied directly to a single training session, and it's not thread-safe
   * so all access must be synchronized.
   *
   * @property progress the [QuestionAssessmentProgress] corresponding to the session
   * @property sessionId the GUID corresponding to the session
   * @property ephemeralQuestionFlow the [MutableStateFlow] that the updated [EphemeralQuestion] is
   *     delivered to
   * @property commandQueue the actor command queue executing all messages that change this state
   */
  private class ControllerState(
    //val progress: SurveyProgress,
    val sessionId: String,
    val ephemeralQuestionFlow: MutableStateFlow<AsyncResult<EphemeralSurveyQuestion>>,
    val commandQueue: SendChannel<ControllerMessage<*>>
  ) {
    /**
     * The list of [SurveyQuestion]s currently being played in the training session.
     *
     * Because this is updated based on [ControllerMessage.ReceiveQuestionList], it may not be
     * initialized at the beginning of a training session. Callers should check
     * [isQuestionsListInitialized] prior to accessing this field.
     */
    lateinit var questionsList: List<SurveyQuestion>

    /** Indicates whether [questionsList] is initialized with values. */
    val isQuestionsListInitialized: Boolean
      get() = ::questionsList.isInitialized
  }
}
