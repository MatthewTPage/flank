package ftl.json

import com.google.api.services.testing.model.TestMatrix
import ftl.util.FTLError
import ftl.util.FailedMatrixError
import ftl.util.IncompatibleTestDimensionError
import ftl.util.InfrastructureError
import ftl.util.MatrixCanceledError
import ftl.util.MatrixState
import okhttp3.internal.toImmutableMap

data class MatrixMap(private val internalMap: MutableMap<String, SavedMatrix>, val runPath: String) {
    val map = internalMap.toImmutableMap()
    fun update(id: String, savedMatrix: SavedMatrix) {
        internalMap[id] = savedMatrix
    }
}

fun MatrixMap.isAllSuccessful() = map.values.any(SavedMatrix::isFailed).not()

fun Iterable<TestMatrix>.updateMatrixMap(matrixMap: MatrixMap) = forEach { matrix ->
    matrixMap.map[matrix.testMatrixId]?.updateWithMatrix(matrix)?.let {
        matrixMap.update(matrix.testMatrixId, it)
    }
}

/**
 * There are two sources of information for detecting the exit code
 * 1) Matrix state via test API (MatrixState.kt)
 * 2) Step outcome via tool results API (Outcome.kt)
 *
 * A test that fails will have a matrix state of finished with an outcome of failure.
 * A matrix state of error means FTL had an infrastructure failure
 * A step outcome of failure means at least one test failed.
 *
 * @param shouldIgnore [Boolean]
 *        set {true} to ignore failed matrices and exit with status code 0
 *
 * @throws MatrixCanceledError [MatrixCanceledError]
 *         at least one matrix canceled by user
 * @throws InfrastructureError [InfrastructureError]
 *         at least one matrix have a test infrastructure error
 * @throws IncompatibleTestDimensionError [IncompatibleTestDimensionError]
 *         at least one matrix have a incompatible test dimensions. This error might occur if the selected Android API level is not supported by the selected device type.
 * @throws FTLError [FTLError]
 *         at least one matrix have unexpected error
 */

fun MatrixMap.validate(shouldIgnore: Boolean = false) {
    map.values.run {
        firstOrNull { it.canceledByUser() }?.let { throw MatrixCanceledError(it.outcomeDetails.orEmpty()) }
        firstOrNull { it.infrastructureFail() }?.let { throw InfrastructureError(it.outcomeDetails.orEmpty()) }
        firstOrNull { it.incompatibleFail() }?.let { throw IncompatibleTestDimensionError(it.outcomeDetails.orEmpty()) }
        firstOrNull { it.state != MatrixState.FINISHED }?.let { throw FTLError(it) }
        filter { it.isFailed() }.let {
            if (it.isNotEmpty()) throw FailedMatrixError(
                matrices = it,
                ignoreFailed = shouldIgnore
            )
        }
    }
}
