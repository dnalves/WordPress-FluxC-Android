package org.wordpress.android.fluxc.example

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.support.v7.util.DiffUtil
import android.support.v7.util.DiffUtil.DiffResult
import android.support.v7.widget.DividerItemDecoration
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.support.v7.widget.RecyclerView.ViewHolder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dagger.android.AndroidInjection
import kotlinx.android.synthetic.main.post_list_activity.*
import kotlinx.coroutines.experimental.DefaultDispatcher
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import kotlinx.coroutines.experimental.withContext
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.wordpress.android.fluxc.Dispatcher
import org.wordpress.android.fluxc.generated.PostActionBuilder
import org.wordpress.android.fluxc.model.PostModel
import org.wordpress.android.fluxc.model.SiteModel
import org.wordpress.android.fluxc.model.list.ListDescriptor
import org.wordpress.android.fluxc.model.list.ListDescriptor.PostListDescriptor
import org.wordpress.android.fluxc.model.list.ListItemDataSource
import org.wordpress.android.fluxc.model.list.ListManager
import org.wordpress.android.fluxc.model.list.PostListFilter
import org.wordpress.android.fluxc.store.ListStore
import org.wordpress.android.fluxc.store.ListStore.OnListChanged
import org.wordpress.android.fluxc.store.ListStore.OnListItemsChanged
import org.wordpress.android.fluxc.store.PostStore
import org.wordpress.android.fluxc.store.PostStore.RemotePostPayload
import org.wordpress.android.fluxc.store.SiteStore
import java.util.Random
import javax.inject.Inject

private const val LOCAL_SITE_ID = "LOCAL_SITE_ID"

class PostListActivity : AppCompatActivity() {
    @Inject internal lateinit var dispatcher: Dispatcher
    @Inject internal lateinit var listStore: ListStore
    @Inject internal lateinit var postStore: PostStore
    @Inject internal lateinit var siteStore: SiteStore

    private lateinit var listDescriptor: PostListDescriptor
    private lateinit var site: SiteModel
    private var postListAdapter: PostListAdapter? = null
    private lateinit var listManager: ListManager<PostModel>
    private var refreshListDataJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.post_list_activity)

        dispatcher.register(this)
        site = siteStore.getSiteByLocalId(intent.getIntExtra(LOCAL_SITE_ID, 0))
        dispatcher.dispatch(PostActionBuilder.newRemoveAllPostsAction())
        listDescriptor = randomizeListDescriptor()
        title = listDescriptor.filter.value
        runBlocking { listManager = getListDataFromStore(listDescriptor) }

        setupViews()

        listManager.refresh()
    }

    override fun onStop() {
        super.onStop()
        dispatcher.unregister(this)
    }

    private fun setupViews() {
        recycler.layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        recycler.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))

        postListAdapter = PostListAdapter(this, listManager)
        recycler.adapter = postListAdapter

        swipeToRefresh.setOnRefreshListener {
            listDescriptor = randomizeListDescriptor()
            title = listDescriptor.filter.value
            refreshListManagerFromStore(listDescriptor, true)
        }
    }

    private fun randomizeListDescriptor(): PostListDescriptor {
        var filters = PostListFilter.values().asList()
        if(this::listDescriptor.isInitialized) {
            filters = filters.filter { it != listDescriptor.filter } // select any other filter
        }
        val selectedFilter = filters[Random().nextInt(filters.size)]
        return PostListDescriptor(site.id, selectedFilter, searchQuery = "fluxc")
    }

    private fun refreshListManagerFromStore(listDescriptor: ListDescriptor, fetchAfter: Boolean) {
        refreshListDataJob?.cancel()
        refreshListDataJob = launch(UI) {
            val listManager = withContext(DefaultDispatcher) { getListDataFromStore(listDescriptor) }
            if (isActive && this@PostListActivity.listDescriptor == listDescriptor) {
                val diffResult = withContext(DefaultDispatcher) {
                    DiffUtil.calculateDiff(DiffCallback(this@PostListActivity.listManager, listManager))
                }
                if (isActive && this@PostListActivity.listDescriptor == listDescriptor) {
                    updateListManager(listManager, diffResult)
                    if (fetchAfter) {
                        listManager.refresh()
                    }
                }
            }
        }
    }

    private fun updateListManager(listManager: ListManager<PostModel>, diffResult: DiffResult) {
        this.listManager = listManager
        swipeToRefresh.isRefreshing = listManager.isFetchingFirstPage
        loadingMoreProgressBar.visibility = if (listManager.isLoadingMore) View.VISIBLE else View.GONE
        postListAdapter?.setListManager(listManager, diffResult)
    }

    private suspend fun getListDataFromStore(listDescriptor: ListDescriptor): ListManager<PostModel> =
        listStore.getListManager(listDescriptor, object : ListItemDataSource<PostModel> {
            override fun fetchItem(listDescriptor: ListDescriptor, remoteItemId: Long) {
                val postToFetch = PostModel()
                postToFetch.remotePostId = remoteItemId
                val payload = RemotePostPayload(postToFetch, site)
                dispatcher.dispatch(PostActionBuilder.newFetchPostAction(payload))
            }

            override fun getItems(listDescriptor: ListDescriptor, remoteItemIds: List<Long>): Map<Long, PostModel> {
                return postStore.getPostsByRemotePostIds(remoteItemIds, site)
            }
        })

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    @Suppress("unused")
    fun onListChanged(event: OnListChanged) {
        if (!event.listDescriptors.contains(listDescriptor)) {
            return
        }
        refreshListManagerFromStore(listDescriptor, false)
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    @Suppress("unused")
    fun onListItemsChanged(event: OnListItemsChanged) {
        if (!listDescriptor.compareIdentifier(event.listDescriptorIdentifier)) {
            return
        }
        refreshListManagerFromStore(listDescriptor, false)
    }

    companion object {
        fun newInstance(context: Context, localSiteId: Int): Intent {
            val intent = Intent(context, PostListActivity::class.java)
            intent.putExtra(LOCAL_SITE_ID, localSiteId)
            return intent
        }
    }

    private class PostListAdapter(
        context: Context,
        private var listManager: ListManager<PostModel>
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val layoutInflater = LayoutInflater.from(context)

        fun setListManager(listManager: ListManager<PostModel>, diffResult: DiffResult) {
            this.listManager = listManager
            diffResult.dispatchUpdatesTo(this)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = layoutInflater.inflate(R.layout.post_list_row, parent, false)
            return PostViewHolder(view)
        }

        override fun getItemCount(): Int {
            return listManager.size
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val postHolder = holder as PostViewHolder
            val postModel = listManager.getRemoteItem(position)
            val title = postModel?.title ?: "Loading.."
            postHolder.postTitle.text = "${position + 1} - ${listManager.getRemoteItemId(position)} - $title"
        }

        private class PostViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val postTitle: TextView = itemView.findViewById(R.id.post_list_row_post_title) as TextView
        }
    }
}

class DiffCallback(
    private val old: ListManager<PostModel>,
    private val new: ListManager<PostModel>
) : DiffUtil.Callback() {
    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return old.getRemoteItemId(oldItemPosition) == new.getRemoteItemId(newItemPosition)
    }

    override fun getOldListSize(): Int {
        return old.size
    }

    override fun getNewListSize(): Int {
        return new.size
    }

    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        val oldItem = old.getRemoteItem(oldItemPosition, false, false)
        val newItem = new.getRemoteItem(newItemPosition, false, false)
        return (oldItem == null && newItem == null) || (oldItem != null &&
                newItem != null && oldItem.title == newItem.title)
    }
}
