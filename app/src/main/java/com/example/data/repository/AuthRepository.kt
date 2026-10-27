package com.example.data.repository

import com.example.data.api.NetworkClient
import com.example.data.api.NetworkErrorHandler
import com.example.data.model.LoginRequest
import com.example.data.model.RegisterRequest
import com.example.data.model.User
import com.example.data.preferences.AppPreferences
import com.example.util.Resource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

interface AuthRepository {
    fun register(email: String, password: String): Flow<Resource<String>>
    fun login(email: String, password: String): Flow<Resource<User>>
    fun isLoggedIn(): Flow<Boolean>
    fun getCurrentUserEmail(): Flow<String?>
    fun getCurrentUserId(): Flow<String?>
    fun getCurrentUserRole(): Flow<String?>
    suspend fun logout()
}

class AuthRepositoryImpl(
    private val preferences: AppPreferences,
    private val networkClient: NetworkClient
) : AuthRepository {

    override fun register(email: String, password: String): Flow<Resource<String>> = flow {
        emit(Resource.Loading)
        try {
            val baseUrl = preferences.baseUrlFlow.first()
            val api = networkClient.getApiService(baseUrl)
            val response = api.register(RegisterRequest(email = email.trim(), password = password))

            if (response.isSuccessful) {
                val body = response.body()
                val message = body?.message ?: "Account created successfully. Please log in."
                emit(Resource.Success(message))
            } else {
                emit(Resource.Error(NetworkErrorHandler.parseError(response)))
            }
        } catch (e: Exception) {
            emit(Resource.Error(NetworkErrorHandler.parseException(e), e))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Authenticates the user against POST /login.
     *
     * BACKEND LIMITATION NOTE:
     * As documented, the Express backend does not issue session cookies or JWT tokens.
     * The returned user object is stored locally strictly for display convenience and
     * email lookup in subsequent requests.
     */
    override fun login(email: String, password: String): Flow<Resource<User>> = flow {
        emit(Resource.Loading)
        try {
            val baseUrl = preferences.baseUrlFlow.first()
            val api = networkClient.getApiService(baseUrl)
            val response = api.login(LoginRequest(email = email.trim(), password = password))

            if (response.isSuccessful) {
                val body = response.body()
                val user = body?.data?.user ?: User(email = email.trim(), role = "user")
                // Persist locally for app navigation convenience
                preferences.saveUserSession(
                    email = user.email,
                    id = user.effectiveId,
                    role = user.role
                )
                emit(Resource.Success(user))
            } else {
                emit(Resource.Error(NetworkErrorHandler.parseError(response)))
            }
        } catch (e: Exception) {
            emit(Resource.Error(NetworkErrorHandler.parseException(e), e))
        }
    }.flowOn(Dispatchers.IO)

    override fun isLoggedIn(): Flow<Boolean> = preferences.isLoggedInFlow
    override fun getCurrentUserEmail(): Flow<String?> = preferences.userEmailFlow
    override fun getCurrentUserId(): Flow<String?> = preferences.userIdFlow
    override fun getCurrentUserRole(): Flow<String?> = preferences.userRoleFlow

    override suspend fun logout() {
        preferences.clearSession()
    }
}
