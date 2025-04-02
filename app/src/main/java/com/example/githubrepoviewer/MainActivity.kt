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
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

// data class for a github repository
data class Repository(
    val id: Long,
    val name: String,
    val description: String?
)

// retrofit interface for github api
interface GitHubApiService {
    @GET("users/{username}/repos")
    suspend fun getRepos(
        @Path("username") username: String,
        @Query("page") page: Int,
        @Query("per_page") perPage: Int = 30
    ): Response<List<Repository>>
}

// class to get repositories from github and check pagination
class GitHubRemoteDataSource(private val api: GitHubApiService) {
    // fetch repos and return a pair (list, hasMore flag)
    suspend fun fetchRepos(username: String, page: Int): Result<Pair<List<Repository>, Boolean>> {
        return try {
            val response = api.getRepos(username, page)
            if (response.isSuccessful) {
                val repos = response.body() ?: emptyList()
                // check link header for next page info
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

// ui state for the repo screen
data class RepoUiState(
    val repos: List<Repository> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val hasMore: Boolean = false
)

// viewmodel to manage state and fetch data
class GitHubRepoViewModel : androidx.lifecycle.ViewModel() {
    private val _uiState = MutableStateFlow(RepoUiState())
    val uiState: StateFlow<RepoUiState> = _uiState

    // keep track of current page and username for pagination
    private var currentPage = 1
    private var currentUsername: String = ""

    // set up moshi for json parsing
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    // set up retrofit with moshi
    private val retrofit = Retrofit.Builder()
        .baseUrl("https://api.github.com/")
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    private val api: GitHubApiService = retrofit.create(GitHubApiService::class.java)
    private val dataSource = GitHubRemoteDataSource(api)

    // start a new search for repos
    fun searchRepos(username: String) {
        // reset state for new search
        currentUsername = username
        currentPage = 1
        _uiState.value = RepoUiState(isLoading = true)
        viewModelScope.launch {
            val result = dataSource.fetchRepos(username, currentPage)
            result.fold(
                onSuccess = { (repos, hasMore) ->
                    _uiState.value = RepoUiState(
                        repos = repos,
                        isLoading = false,
                        hasMore = hasMore
                    )
                },
                onFailure = { error ->
                    _uiState.value = RepoUiState(isLoading = false, error = error.message)
                }
            )
        }
    }

    // load the next page of repos
    fun loadMore() {
        if (_uiState.value.isLoading || !_uiState.value.hasMore) return

        _uiState.value = _uiState.value.copy(isLoading = true)
        currentPage++
        viewModelScope.launch {
            val result = dataSource.fetchRepos(currentUsername, currentPage)
            result.fold(
                onSuccess = { (repos, hasMore) ->
                    // add new repos to the current list
                    val updatedList = _uiState.value.repos + repos
                    _uiState.value = RepoUiState(
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

// main activity that shows the ui
class MainActivity : ComponentActivity() {
    private val viewModel: GitHubRepoViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                GitHubRepoScreen(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun GitHubRepoScreen(viewModel: GitHubRepoViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var username by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // add some top space
        Spacer(modifier = Modifier.height(32.dp))
        // row with input field and search button
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

        // show error, loading, or repo list
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
                    Text("No repositories to display. Please enter a username.")
                }
            }
            else -> {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(uiState.repos) { repo ->
                        RepoItem(repo)
                    }
                }
                // show load more button if there are more pages
                if (uiState.hasMore) {
                    Button(
                        onClick = { viewModel.loadMore() },
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
fun RepoItem(repo: Repository) {
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
