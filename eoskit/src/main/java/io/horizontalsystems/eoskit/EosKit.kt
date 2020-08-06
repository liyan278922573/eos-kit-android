package io.horizontalsystems.eoskit

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import io.horizontalsystems.eoskit.core.InvalidPrivateKey
import io.horizontalsystems.eoskit.core.NotStartedState
import io.horizontalsystems.eoskit.core.Token
import io.horizontalsystems.eoskit.managers.ActionManager
import io.horizontalsystems.eoskit.managers.BalanceManager
import io.horizontalsystems.eoskit.managers.TransactionManager
import io.horizontalsystems.eoskit.models.Action
import io.horizontalsystems.eoskit.models.Balance
import io.horizontalsystems.eoskit.models.Transaction
import io.horizontalsystems.eoskit.storage.KitDatabase
import io.horizontalsystems.eoskit.storage.Storage
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.subjects.PublishSubject
import one.block.eosiojava.error.utilities.EOSFormatterError
import one.block.eosiojava.implementations.ABIProviderImpl
import one.block.eosiojava.interfaces.IABIProvider
import one.block.eosiojava.interfaces.IRPCProvider
import one.block.eosiojava.interfaces.ISerializationProvider
import one.block.eosiojava.interfaces.ISignatureProvider
import one.block.eosiojava.models.rpcProvider.Authorization
import one.block.eosiojava.session.TransactionSession
import one.block.eosiojava.utilities.EOSFormatter
import one.block.eosiojava.utilities.PEMProcessor
import one.block.eosiojavaabieosserializationprovider.AbiEosSerializationProviderImpl
import one.block.eosiojavarpcprovider.implementations.EosioJavaRpcProviderImpl
import one.block.eosiosoftkeysignatureprovider.SoftKeySignatureProviderImpl
import org.bouncycastle.util.encoders.Hex
import java.math.BigDecimal
import java.util.concurrent.TimeUnit


class EosKit(
        val account: String,
        private val balanceManager: BalanceManager,
        private val actionManager: ActionManager,
        private val transactionManager: TransactionManager,
        private val networkType: NetworkType)
    : BalanceManager.Listener, ActionManager.Listener {

    var irreversibleBlockHeight: Int? = actionManager.irreversibleBlockHeight
    val irreversibleBlockFlowable: Flowable<Int>
        get() = irreversibleBlockSubject.toFlowable(BackpressureStrategy.BUFFER)

    private val tokens = mutableListOf<Token>()
    private val irreversibleBlockSubject = PublishSubject.create<Int>()

    fun register(token: String, symbol: String): Token {
        val newBalance = balanceManager.getBalance(symbol)
        val newToken = Token(token, symbol).apply {
            balance = newBalance?.value ?: BigDecimal(0)
        }

        tokens.add(newToken)
        balanceManager.sync(account, newToken)

        return newToken
    }

    fun unregister(token: Token) {
        tokens.removeAll { it == token }
    }

    fun refresh() {
        tokens.forEach { token ->
            balanceManager.sync(account, token)
        }

        actionManager.sync(account)
    }

    fun stop() {
        balanceManager.stop()
        actionManager.stop()
    }

    //should run in background
    @Throws
    fun validate(account: String){
        actionManager.validateAccount(account)
    }

    @Throws
    fun send(token: Token, to: String, amount: BigDecimal, memo: String): Single<String> {
        return transactionManager
                .send(account, token.token, to, "$amount ${token.symbol}", memo)
                .doOnSuccess {
                    Observable.timer(2, TimeUnit.SECONDS).subscribe {
                        balanceManager.sync(account, token)
                    }
                }
    }
    @Throws
    fun send2(account:String,token: Token, publicKey: String): Single<String> {
        return transactionManager
            .send2(account, token.token, publicKey)
            .doOnSuccess {
                Observable.timer(2, TimeUnit.SECONDS).subscribe {
                    balanceManager.sync(account, token)
                }
                
            }
    }
    @Throws
    fun send_json(account: String,method:String,token:Token,reqJson:String): Single<String> {
        return transactionManager
            .send_json(account, method, "bacc",reqJson)
            .doOnSuccess {
                Observable.timer(2, TimeUnit.SECONDS).subscribe {
                    balanceManager.sync(account, token)
                }
            }
    }

    fun transactions(token: Token, fromSequence: Int? = null, limit: Int? = null): Single<List<Transaction>> {
        return actionManager
                .getActions(account, token, fromSequence, limit)
                .map { list -> list.map { Transaction(it) } }
    }

    fun statusInfo(): Map<String, Any> {
        val statusInfo = LinkedHashMap<String, Any>()

        statusInfo["Irreversible Block Height"] = irreversibleBlockHeight ?: "N/A"
        statusInfo["Sync State"] = getKitSyncState()
        statusInfo["RPC Host"] = getRpcHost(networkType)

        return statusInfo
    }

    private fun getKitSyncState(): String {
        val syncState = when {
            tokens.any { it.syncState == SyncState.Syncing } -> SyncState.Syncing
            tokens.any { it.syncState is SyncState.NotSynced } -> SyncState.NotSynced(NotStartedState())
            else -> SyncState.Synced
        }
        return syncState.getName()
    }

    // BalanceManager Listener

    override fun onSyncBalance(balance: Balance) {
        tokenBy(balance.token, balance.symbol)?.let { token ->
            token.balance = balance.value
            token.syncState = SyncState.Synced
        }
    }

    override fun onSyncBalanceFail(token: String, error: Throwable) {
        tokens.find { it.token == token }?.syncState = SyncState.NotSynced(error)
    }

    // ActionManager Listener

    override fun onSyncActions(actions: List<Action>) {
        actions.groupBy { it.account }.forEach { (token, acts) ->
            acts.map { Transaction(it) }
                    .groupBy { it.symbol }
                    .forEach { (symbol, transactions) ->
                        tokenBy(token, symbol)?.transactionsSubject?.onNext(transactions)
                    }
        }
    }

    override fun onChangeLastIrreversibleBlock(height: Int) {
        irreversibleBlockHeight = height
        irreversibleBlockSubject.onNext(height)
    }

    private fun tokenBy(name: String, symbol: String?): Token? {
        return tokens.find { it.token == name && it.symbol == symbol }
    }

    // SyncState

    sealed class SyncState {
        object Synced: SyncState()
        class NotSynced(val error: Throwable): SyncState()
        object Syncing: SyncState()

        fun getName(): String{
            return when(this){
                Synced -> "Synced"
                Syncing -> "Syncing"
                is NotSynced -> "Not Synced"
            }
        }
    }

    enum class NetworkType(chainId: String) {
        MainNet("25f43e0eb6d8717c51e0b7a56c1ed2ddd7e3672d53447639832bb97ae536fe92"), // EOS
        TestNet("25f43e0eb6d8717c51e0b7a56c1ed2ddd7e3672d53447639832bb97ae536fe92")  // JUNGLE
    }

    companion object {

        private fun getRpcHost(networkType: NetworkType): String = when (networkType) {
            NetworkType.MainNet -> "http://47.89.25.218:8001"
            NetworkType.TestNet -> "http://47.89.25.218:8001"
        }

        fun instance(context: Context, account: String, privateKey: String, networkType: NetworkType = NetworkType.MainNet, walletId: String = "unique-id"): EosKit {
            val database = KitDatabase.create(context, getDatabaseName(networkType, walletId))
            val storage = Storage(database)

            val rpcProvider = EosioJavaRpcProviderImpl("http://47.89.25.218:8001")
            val serializationProvider = AbiEosSerializationProviderImpl()
            val abiProvider = ABIProviderImpl(rpcProvider, serializationProvider)
            val signatureProvider = SoftKeySignatureProviderImpl().apply {
                importKey(privateKey)
            }

            val balanceManager = BalanceManager(storage, rpcProvider)
            val actionManager = ActionManager(storage, rpcProvider)
            val transactionManager = TransactionManager(rpcProvider, signatureProvider, serializationProvider, abiProvider)

            val eosKit = EosKit(account, balanceManager, actionManager, transactionManager, networkType)

            balanceManager.listener = eosKit
            actionManager.listener = eosKit

            return eosKit
        }
        fun getKeyData():String
        {
            val pemFormattedPrivateKey = "MDECAQEEIFjJPuD5efj0AdOolGUxlte5szjCItDfSLDtWjJio4AroAoGCCqGSM49AwEH".trimIndent()
            val pemProcessor = PEMProcessor(pemFormattedPrivateKey)
             return Hex.toHexString(pemProcessor.keyData)
        }
        fun clear(context: Context, networkType: NetworkType, walletId: String) {
            SQLiteDatabase.deleteDatabase(context.getDatabasePath(getDatabaseName(networkType, walletId)))
        }

        fun validatePrivateKey(key: String) {
            try {
                EOSFormatter.convertEOSPrivateKeyToPEMFormat(key)
            } catch (e: EOSFormatterError) {
                throw InvalidPrivateKey()
            }
        }

        private fun getDatabaseName(networkType: NetworkType, walletId: String): String {
            return "Eos-$networkType-$walletId"
        }
    }
}
