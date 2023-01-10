package c5.example.meeting.flows

import net.corda.v5.application.crypto.DigitalSignatureAndMetadata
import net.corda.v5.application.flows.*
import net.corda.v5.application.marshalling.JsonMarshallingService
import net.corda.v5.application.membership.MemberLookup
import net.corda.v5.application.messaging.FlowMessaging
import net.corda.v5.application.messaging.FlowSession
import net.corda.v5.application.serialization.SerializationService
import net.corda.v5.base.annotations.CordaSerializable
import net.corda.v5.base.annotations.Suspendable
import net.corda.v5.base.types.MemberX500Name
import net.corda.v5.base.util.contextLogger
import net.corda.v5.crypto.SecureHash
import net.corda.v5.ledger.consensual.ConsensualLedgerService
import net.corda.v5.ledger.consensual.ConsensualState
import net.corda.v5.ledger.consensual.transaction.ConsensualLedgerTransaction
import java.security.PublicKey
import java.time.LocalDateTime

/**
 * A flow to book a meeting which requires all participants to accept
 */

@InitiatingFlow("meeting-booking")
class MeetingBookingFlow : RPCStartableFlow{
    private companion object {
        val log = contextLogger()
    }

    @CordaInject
    lateinit var flowMessaging: FlowMessaging

    @CordaInject
    lateinit var consensualLedgerService: ConsensualLedgerService

    @CordaInject
    lateinit var jsonMarshallingService: JsonMarshallingService

    @CordaInject
    lateinit var memberLookup: MemberLookup

    @Suspendable
    override fun call(requestBody: RPCRequestData): String {
        val meetingRequest = requestBody.getRequestBodyAs(jsonMarshallingService, MeetingRequest::class.java)
        val meetingDetail = meetingRequest.meetingDetail
        val participants = meetingRequest.participants

        val meetingState = MeetingConsensualState(meetingDetail, participants.map { getPublicKey(it) })

        val txBuilder = consensualLedgerService.getTransactionBuilder()

        @Suppress("DEPRECATION")
        val signedTransaction = txBuilder
            .withStates(meetingState)
            .toSignedTransaction(memberLookup.myInfo().ledgerKeys.first())

        val sessions = initiateSessions(participants.minus(memberLookup.myInfo().name))
        val result = consensualLedgerService.finalize(signedTransaction, sessions)

        val output = MeetingRequestResult(
            result.id,
            meetingDetail,
            "Booked",
            result.signatures.map { getMemberFromSignature(it) }.toSet()
        )

        return jsonMarshallingService.format(output)

    }

    @Suspendable
    private fun getMemberFromSignature(signature: DigitalSignatureAndMetadata) =
        memberLookup.lookup(signature.by)?.name ?: error("Member for consensual signature not found")

    @Suspendable
    private fun initiateSessions(participants: List<MemberX500Name>) =
        participants.filterNot { it == memberLookup.myInfo().name }.map { flowMessaging.initiateFlow(it) }

    @Suspendable
    private fun getPublicKey(member: MemberX500Name): PublicKey {
        val memberInfo = memberLookup.lookup(member) ?: error("Member \"$member\" not found")
        return memberInfo.ledgerKeys.firstOrNull() ?: error("Member \"$member\" does not have any ledger keys")
    }

}

@InitiatedBy("meeting-booking")
class MeetingBookingResponderFlow: ResponderFlow{

    private companion object {
        val log = contextLogger()
    }

    @CordaInject
    lateinit var consensualLedgerService: ConsensualLedgerService

    @CordaInject
    lateinit var memberLookup: MemberLookup

    @Suspendable
    override fun call(session: FlowSession) {
        val finalizedSignedTransaction = consensualLedgerService.receiveFinality(session) {
            val meetingState = it.states[0] as MeetingConsensualState
            log.info("\"${memberLookup.myInfo().name}\" " +
                    "got the meeting request with agenda ${meetingState.meetingDetail.agenda}")
        }
        val requiredSignatories = finalizedSignedTransaction.toLedgerTransaction().requiredSignatories
        val actualSignatories = finalizedSignedTransaction.signatures.map {it.by}.toSet()
        check(requiredSignatories == actualSignatories) {
            "Signatories were not as expected. Expected:\n    " + requiredSignatories.joinToString("\n    ") +
                    "and got:\n    " + actualSignatories.joinToString("\n    ")
        }
        log.info("Finished responder flow - $finalizedSignedTransaction")
    }
}

class MeetingQueryFlow : RPCStartableFlow {
    @CordaInject
    lateinit var jsonMarshallingService: JsonMarshallingService

    @CordaInject
    lateinit var consensualLedgerService: ConsensualLedgerService

    @CordaInject
    lateinit var memberLookup: MemberLookup

    @Suspendable
    override fun call(requestBody: RPCRequestData): String {
        val txId = requestBody.getRequestBodyAs<MeetingQuery>(jsonMarshallingService).txId
        val tx = consensualLedgerService.findSignedTransaction(txId)

        checkNotNull(tx) {"No consensual ledger transaction was persisted for provided id"}

        return jsonMarshallingService.format(MeeetingQueryResponse(
            (tx.toLedgerTransaction().states[0] as MeetingConsensualState).meetingDetail,
            tx.signatures.map { checkNotNull(memberLookup.lookup(it.by)?.name) }.toSet()
        ))
    }
}

@CordaSerializable
data class MeetingQuery(val txId: SecureHash)

@CordaSerializable
data class MeeetingQueryResponse(
    val meetingDetail: MeetingDetail,
    val participants: Set<MemberX500Name>
)

//TODO Custom Serializer for LocalDateTime
@CordaSerializable
data class MeetingDetail(
    val agenda: String,
    //val time: LocalDateTime,
    val location: String)

@CordaSerializable
class MeetingConsensualState(
    val meetingDetail: MeetingDetail,
    override val participants: List<PublicKey>
    ) : ConsensualState {

    override fun verify(ledgerTransaction: ConsensualLedgerTransaction) {}
}

@CordaSerializable
data class MeetingRequest(
    val meetingDetail: MeetingDetail,
    val participants: List<MemberX500Name>
)

@CordaSerializable
data class MeetingRequestResult(
    val txId: SecureHash,
    val meetingDetail: MeetingDetail,
    val status: String,
    val acceptedBy: Set<MemberX500Name>
)