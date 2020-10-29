package io.horizontalsystems.eoskit.sample

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import io.horizontalsystems.eoskit.EosKit
import io.horizontalsystems.eoskit.models.Transaction
import io.reactivex.disposables.CompositeDisposable

class MainViewModel : ViewModel() {

    val adapters = mutableListOf<EosAdapter>()

    val syncState = MutableLiveData<EosKit.SyncState>()
    val balance = MutableLiveData<String>()
    val transactions = MutableLiveData<Map<String, List<Transaction>>>()
    val lastIrreversibleBlock = MutableLiveData<Int>()

    private val disposables = CompositeDisposable()

    private lateinit var eosKit: EosKit

    init {
        init()
    }

    fun refresh() {
        eosKit.refresh()
    }

    fun clear() {
        init()
    }

    fun updateActions(adapter: EosAdapter) {
        adapter.transactions()
                .subscribe { list -> transactions.postValue(mapOf(adapter.name to list)) }
                .let { disposables.add(it) }
    }

    // Private

    private fun init() {
        eosKit = EosKit.instance(App.instance, "itestio2", "5HtVmZpEbEm7EV2VTwW86qguy9m7Sy4CVwiFoPraym6ciFnc1k9", EosKit.NetworkType.MainNet)
        adapters.add(EosAdapter(eosKit, "bacc.token", "BAS"))
//        adapters.add(EosAdapter(eosKit, "bacc", "BAS"))

        adapters.forEach { adapter ->

            updateBalance(adapter)
            updateActions(adapter)

            adapter.balanceFlowable.subscribe {
                balance.postValue("${adapter.balance} ${adapter.name}")
            }

            adapter.syncStateFlowable.subscribe {
                syncState.postValue(adapter.syncState)
            }

            adapter.transactionsFlowable.subscribe {
                updateActions(adapter)
            }

            adapter.irreversibleBlockFlowable.subscribe {
                lastIrreversibleBlock.postValue(eosKit.irreversibleBlockHeight)
            }
        }

        eosKit.refresh()
    }

    private fun updateBalance(adapter: EosAdapter) {
        balance.postValue("${adapter.balance} ${adapter.name}")
    }
}
