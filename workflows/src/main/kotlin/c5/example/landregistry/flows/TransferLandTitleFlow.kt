package c5.example.landregistry.flows

import c5.example.landregistry.contracts.LandTitleContract
import c5.example.landregistry.states.LandTitleState
import net.corda.v5.application.crypto.DigitalSignatureAndMetadata
import net.corda.v5.application.crypto.DigitalSignatureMetadata
import net.corda.v5.application.crypto.SigningService
import net.corda.v5.application.flows.ResponderFlow
import net.corda.v5.application.flows.InitiatingFlow
import net.corda.v5.application.flows.RPCStartableFlow
import net.corda.v5.application.flows.CordaInject
import net.corda.v5.application.flows.RPCRequestData
import net.corda.v5.application.flows.InitiatedBy
import net.corda.v5.application.flows.getRequestBodyAs
import net.corda.v5.application.marshalling.JsonMarshallingService
import net.corda.v5.application.membership.MemberLookup
import net.corda.v5.application.messaging.FlowMessaging
import net.corda.v5.application.messaging.FlowSession
import net.corda.v5.application.messaging.receive
import net.corda.v5.application.serialization.SerializationService
import net.corda.v5.base.annotations.Suspendable
import net.corda.v5.base.types.MemberX500Name
import net.corda.v5.base.util.contextLogger
import net.corda.v5.base.util.days
import net.corda.v5.crypto.SecureHash
import net.corda.v5.crypto.SignatureSpec
import net.corda.v5.ledger.common.Party
import net.corda.v5.ledger.utxo.UtxoLedgerService
import net.corda.v5.ledger.utxo.transaction.UtxoSignedTransaction
import java.time.Instant
import java.time.LocalDateTime

@InitiatingFlow("transfer-title")
class TransferLandTitleFlow : RPCStartableFlow {

    private companion object {
        val log = contextLogger()
    }

    @CordaInject
    lateinit var jsonMarshallingService: JsonMarshallingService

    @CordaInject
    lateinit var memberLookup: MemberLookup

    @CordaInject
    lateinit var utxoLedgerService: UtxoLedgerService

    @CordaInject
    lateinit var flowMessaging: FlowMessaging

    @Suspendable
    override fun call(requestBody: RPCRequestData): String {
        val request = requestBody.getRequestBodyAs<TransferLandTitleRequest>(jsonMarshallingService)
        val myInfo = memberLookup.myInfo()
        val owner = memberLookup.lookup(request.owner)
            ?: throw IllegalArgumentException("Unknown holder: ${request.owner}.")

//        Unable to fetch old state, error: org.apache.avro.UnresolvedUnionException: Not in union, Using txId instead
//        val oldState = utxoLedgerService.findUnconsumedStatesByType(LandTitleState::class.java).filter {
//            it.state.contractState.titleNumber.equals(request.titleNumber)
//        }.first()

        val signedTransaction = utxoLedgerService.findSignedTransaction(SecureHash.parse(request.txId))?:
            throw IllegalArgumentException("Transaction with id: ${request.txId} does not exist.")
        val oldStateAndRef = signedTransaction.toLedgerTransaction().outputStateAndRefs.first()
        val oldState = oldStateAndRef.state.contractState as LandTitleState

        val landTitleState = LandTitleState(
            oldState.titleNumber,
            oldState.location,
            oldState.areaInSquareMeter,
            oldState.extraDetails,
            LocalDateTime.now(),
            owner.ledgerKeys.first(),
            myInfo.ledgerKeys.first()
        )

        val notaryKey = memberLookup.lookup().first {
            it.memberProvidedContext["corda.notary.service.name"] == oldStateAndRef.state.notary.name.toString()
        }.ledgerKeys.first()
        val transaction = utxoLedgerService
            .getTransactionBuilder()
            .setTimeWindowBetween(Instant.now(), Instant.now().plusMillis(1.days.toMillis()))
            .setNotary(Party(oldStateAndRef.state.notary.name, notaryKey))
            .addInputState(oldStateAndRef.ref)
            .addOutputState(landTitleState)
            .addCommand(LandTitleContract.TransferLandTitle())
            // Cannot Get Signature from CP
            //.addSignatories(listOf(landTitleState.issuer, landTitleState.owner, oldState.owner))
            .addSignatories(listOf(myInfo.ledgerKeys.first()))

        @Suppress("DEPRECATION")
        var partiallySignedTransaction = transaction.toSignedTransaction(myInfo.ledgerKeys.first())

        val issuer = memberLookup.lookup(oldState.issuer)
            ?: throw IllegalArgumentException("Unknown Issuer: ${oldState.issuer}.")

        val issuerSession = flowMessaging.initiateFlow(issuer.name)
        val ownerSession = flowMessaging.initiateFlow(request.owner)


        // TODO Add CP Signatures
//        issuerSession.send(partiallySignedTransaction)
//        val issuerSignature = issuerSession.receive<DigitalSignatureAndMetadata>()
//
//        ownerSession.send(partiallySignedTransaction)
//        val ownerSignature = ownerSession.receive<DigitalSignatureAndMetadata>()

        val finalizedSignedTransaction = utxoLedgerService.finalize(
            partiallySignedTransaction,
            listOf(issuerSession, ownerSession)
        )

        return finalizedSignedTransaction.id.toString()

    }
}

@InitiatedBy("transfer-title")
class TransferLandTitleResponderFlow: ResponderFlow {

    private companion object {
        val log = contextLogger()
    }

    @CordaInject
    lateinit var utxoLedgerService: UtxoLedgerService

//    @CordaInject
////    lateinit var signingService: SigningService
////
////    @CordaInject
////    lateinit var serializationService: SerializationService
////
////    @CordaInject
////    lateinit var memberLookup: MemberLookup

    @Suspendable
    override fun call(session: FlowSession) {
        //TODO Add Signature - Cant find api to add signature
//        val partiallySignedTransaction = session.receive<UtxoSignedTransaction>()
//
//        val signature = signingService.sign(
//            serializationService.serialize(partiallySignedTransaction).bytes,
//            memberLookup.myInfo().ledgerKeys.first(),
//            SignatureSpec.ECDSA_SHA256
//        )
//        val signatureAndMetadata = DigitalSignatureAndMetadata(
//            signature,
//            DigitalSignatureMetadata(
//                Instant.now(), signature.context
//            )
//        )
//        session.send(signatureAndMetadata)
        utxoLedgerService.receiveFinality(session) {}
    }
}

data class TransferLandTitleRequest(
    val titleNumber: String,
    val owner: MemberX500Name,
    val txId: String
)