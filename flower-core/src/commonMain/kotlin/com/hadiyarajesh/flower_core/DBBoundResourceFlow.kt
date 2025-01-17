/*
 *  Copyright (C) 2022 Rajesh Hadiya
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package com.hadiyarajesh.flower_core

import kotlinx.coroutines.flow.*

/**
 * Fetch the data from local database (if available), perform a network request (if instructed).
 * and emit the response after saving it to local database.
 * Additionally, takes an action to perform if a network request fails.
 * Difference between this function and [dbBoundResource] is that, [dbBoundResource] emits the data only once, while this function will emit [Flow] of data.
 * Moreover, the function called in [makeNetworkRequest] must NOT be a `suspend` function.
 * @author Rajesh Hadiya
 * @param fetchFromLocal - A function to retrieve data from local database
 * @param shouldMakeNetworkRequest - Whether or not to make network request
 * @param makeNetworkRequest - A function to make network request
 * @param processNetworkResponse - A function to process network response (e.g., saving response headers before saving actual data)
 * @param saveResponseData - A function to save network response
 * @param onNetworkRequestFailed - An action to perform when a network request fails
 * @return [Flow] of [DB] type
 */
inline fun <DB, REMOTE> dbBoundResourceFlow(
    crossinline fetchFromLocal: suspend () -> Flow<DB>,
    crossinline shouldMakeNetworkRequest: suspend (DB?) -> Boolean = { true },
    crossinline makeNetworkRequest: () -> Flow<ApiResponse<REMOTE>>,
    crossinline processNetworkResponse: (response: ApiSuccessResponse<REMOTE>) -> Unit = { },
    crossinline saveResponseData: suspend (REMOTE) -> Unit = { },
    crossinline onNetworkRequestFailed: (errorBody: String?, statusCode: Int) -> Unit = { _: String?, _: Int -> }
) = flow<Resource<DB>> {
    emit(Resource.loading(null))
    val localData = fetchFromLocal().first()

    if (shouldMakeNetworkRequest(localData)) {
        emit(Resource.loading(localData))

        makeNetworkRequest().collect { apiResponse ->
            when (apiResponse) {
                is ApiSuccessResponse -> {
                    processNetworkResponse(apiResponse)
                    apiResponse.body?.let { saveResponseData(it) }
                    emitAll(
                        fetchFromLocal().map { dbData ->
                            dbData?.let { Resource.success(data = dbData) }
                                ?: Resource.emptySuccess()
                        }
                    )
                }

                is ApiErrorResponse -> {
                    onNetworkRequestFailed(apiResponse.errorMessage, apiResponse.statusCode)
                    emitAll(
                        fetchFromLocal().map { dbData ->
                            Resource.error(
                                msg = apiResponse.errorMessage,
                                statusCode = apiResponse.statusCode,
                                data = dbData
                            )
                        }
                    )
                }

                is ApiEmptyResponse -> {
                    emit(Resource.emptySuccess())
                }
            }
        }
    } else {
        emitAll(
            fetchFromLocal().map { dbData ->
                dbData?.let { Resource.success(it) } ?: Resource.emptySuccess()
            }
        )
    }
}
