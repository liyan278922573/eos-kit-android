package io.horizontalsystems.eoskit.managers

import io.horizontalsystems.eoskit.core.ErrorUtils
import io.reactivex.Single
import one.block.eosiojava.error.session.TransactionSignAndBroadCastError
import one.block.eosiojava.interfaces.IABIProvider
import one.block.eosiojava.interfaces.IRPCProvider
import one.block.eosiojava.interfaces.ISerializationProvider
import one.block.eosiojava.interfaces.ISignatureProvider
import one.block.eosiojava.models.rpcProvider.Action
import one.block.eosiojava.models.rpcProvider.Authorization
import one.block.eosiojava.session.TransactionSession
import org.json.JSONObject

class NewAccountManager(
    private val rpcProvider: IRPCProvider,
    private val signatureProvider: ISignatureProvider,
    private val serializationProvider: ISerializationProvider,
    private val abiProvider: IABIProvider
) {

    @Throws
    fun send(account: String, token: String, publicKey: String): Single<String> {
        return Single.create { it.onSuccess(process(account, token, publicKey)) }
    }

    @Throws
    private fun process(account: String, token: String, publicKey: String): String {

        val session = TransactionSession(serializationProvider, rpcProvider, abiProvider, signatureProvider)
        val processor = session.transactionProcessor

        val publicKeyJson = JSONObject().apply {
            put("key", publicKey)
            put("weight", 1)
        }
        val keys = arrayOf(publicKeyJson)
        val emp:Array<String?> = emptyArray()
        var str:String = "vid" + account
        val Json = JSONObject().apply {
            put("threshold", 1)
            put("keys", keys)
            put("accounts", emp)
            put("waits", emp)
        }
        val args = JSONObject().apply {
            put("creator", "bacc.test")
            put("name", account)
            put("owner", Json)
            put("active", Json)
            put("vid", str)
        }
        val reqJson = JSONObject().apply {
            put("code", "bacc")
            put("action", "newaccount")
            put("args", args)
        }


        val action = Action(token, "newaccount", listOf(Authorization(account, "active")), reqJson.toString())

        try {
            //  Prepare actions with above actions. A actions can be executed with multiple actions.
            processor.prepare(listOf(action))
            //  Sign and broadcast the actions.
            val response = processor.signAndBroadcast()
            return response.transactionId
        } catch (e: TransactionSignAndBroadCastError) {
            val rpcResponseError = ErrorUtils.getBackendError(e)
            if (rpcResponseError != null) {
                throw ErrorUtils.getBackendErrorFromResponse(rpcResponseError)
            }
        }

        throw Exception()
    }


}
