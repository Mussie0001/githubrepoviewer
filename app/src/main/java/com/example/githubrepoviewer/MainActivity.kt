package com.example.githubrepoviewer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

// data class for a github repository (api integration using moshi)
data class Repository(
    val id: Long,
    val name: String,
    val description: String?
)

// retrofit interface for github api (repositories endpoint)
interface GitHubAPI {
    @GET("users/{username}/repos")
    suspend fun getRepos(
        @Path("username") username: String,
        @Query("page") page: Int,
        @Query("per_page") perPage: Int = 30
    ): Response<List<Repository>>
}

// class to fetch repositories from github and check pagination via the link header
class FetchRepos(private val api: GitHubAPI) {
    // fetch repos and return a pair (list of repos, hasMore flag)
    suspend fun fetchRepos(username: String, page: Int): Result<Pair<List<Repository>, Boolean>> {
        return try {
            val response = api.getRepos(username, page)
            if (response.isSuccessful) {
                val repos = response.body() ?: emptyList()
                // check link header = indicating another page exists
                val linkHeader = response.headers()["Link"]
                val hasMore = linkHeader?.contains("rel=\"next\"") == true
                Result.success(Pair(repos, hasMore))
            } else {
                Result.failure(Exception("error: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

// ui state to hold repository data, loading flag, error and more pages flag
data class UiState(
    val repos: List<Repository> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val hasMore: Boolean = false
)

// viewmodel to manage state and fetch data with flow
class GitHubState : androidx.lifecycle.ViewModel() {
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    private var currentPage = 1
    private var currentUsername: String = ""

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient = OkHttpClient.Builder().build()

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://api.github.com/")
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    private val api: GitHubAPI = retrofit.create(GitHubAPI::class.java)
    private val dataSource = FetchRepos(api)

    // start a new search for repos when the user submits a username
    fun searchRepos(username: String) {
        currentUsername = username
        currentPage = 1
        _uiState.value = UiState(isLoading = true)
        viewModelScope.launch {
            val result = dataSource.fetchRepos(username, currentPage)
            result.fold(
                onSuccess = { (repos, hasMore) ->
                    _uiState.value = UiState(
                        repos = repos,
                        isLoading = false,
                        hasMore = hasMore
                    )
                },
                onFailure = { error ->
                    _uiState.value = UiState(isLoading = false, error = error.message)
                }
            )
        }
    }

    // load the next page of repos
    fun loadNextPages() {
        if (_uiState.value.isLoading || !_uiState.value.hasMore) return
        _uiState.value = _uiState.value.copy(isLoading = true)
        currentPage++
        viewModelScope.launch {
            val result = dataSource.fetchRepos(currentUsername, currentPage)
            result.fold(
                onSuccess = { (repos, hasMore) ->
                    val updatedList = _uiState.value.repos + repos
                    _uiState.value = UiState(
                        repos = updatedList,
                        isLoading = false,
                        hasMore = hasMore
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(isLoading = false, error = error.message)
                }
            )
        }
    }
}

// main activity to set the compose ui
class MainActivity : ComponentActivity() {
    private val vm: GitHubState by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                RepoScreen(viewModel = vm)
            }
        }
    }
}

@Composable
fun RepoScreen(viewModel: GitHubState) {
    val uiState by viewModel.uiState.collectAsState()
    var username by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        // input field and search button in a row for user input
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("GitHub Username") },
                modifier = Modifier.weight(1f)
            )
            Button(onClick = { viewModel.searchRepos(username) }) {
                Text("Search")
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        // display error, loading, or repo list with proper state management
        when {
            uiState.error != null -> {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("error: ${uiState.error}")
                }
            }
            uiState.isLoading && uiState.repos.isEmpty() -> {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            uiState.repos.isEmpty() -> {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("no repositories to display. please enter a username.")
                }
            }
            else -> {
                // displays repository names and descriptions in a lazy column
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(uiState.repos) { repo ->
                        RepoCard(repo)
                    }
                }
                // load more button for pagination
                if (uiState.hasMore) {
                    Button(
                        onClick = { viewModel.loadNextPages() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        if (uiState.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text("load more")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RepoCard(repo: Repository) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = repo.name, style = MaterialTheme.typography.titleMedium)
            repo.description?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = it, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}