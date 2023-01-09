package c5.example.landregistry.flows

import net.corda.v5.application.flows.RPCRequestData
import net.corda.v5.application.flows.RPCStartableFlow
import net.corda.v5.base.util.contextLogger

class FetchLandTitleFlow : RPCStartableFlow {

    private companion object {
        val log = contextLogger()
    }

    override fun call(requestBody: RPCRequestData): String {
        TODO("Not yet implemented")
    }

}