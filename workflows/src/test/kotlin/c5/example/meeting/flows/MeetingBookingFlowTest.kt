package c5.example.meeting.flows

import net.corda.simulator.RequestData
import net.corda.simulator.Simulator
import net.corda.simulator.crypto.HsmCategory
import net.corda.simulator.factories.JsonMarshallingServiceFactory
import net.corda.simulator.factories.ServiceOverrideBuilder
import net.corda.simulator.factories.SimulatorConfigurationBuilder
import net.corda.v5.application.marshalling.JsonMarshallingService
import net.corda.v5.application.marshalling.json.JsonDeserializer
import net.corda.v5.application.marshalling.json.JsonNodeReader
import net.corda.v5.application.marshalling.json.JsonSerializer
import net.corda.v5.application.marshalling.json.JsonWriter
import net.corda.v5.base.types.MemberX500Name
import org.hamcrest.MatcherAssert
import org.hamcrest.Matchers
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MeetingBookingFlowTest {

    @Test
    fun `should get signatures from everyone who needs to sign`() {

        val jsonService = JsonMarshallingServiceFactory.create()

        // Given Alice, Bob and Charlie who need to attend a meeting
        val simulator = Simulator()
        val alice = createMember("Alice")
        val bob = createMember("Bob")
        val charlie = createMember("Charlie")

        val nodes = listOf(alice, bob, charlie).map {
            val node = simulator.createVirtualNode(it, MeetingBookingFlow::class.java,
                MeetingBookingResponderFlow::class.java)
            node.generateKey("${it.commonName}-key", HsmCategory.LEDGER, "any-scheme")
            node
        }

        //TODO Custom Serializer for LocalDateTime
        val meetingDetail = MeetingDetail(
            "TechInterview",
            "15 Jan 2023 10:00",
            "Meeting Room X"
        )
        val requestData = RequestData.create(
            "r1",
            MeetingBookingFlow::class.java,
            MeetingRequest(meetingDetail, listOf(alice, bob, charlie))
        )

        // Then the meeting should have been booked
        val result = jsonService.parse(nodes[0].callFlow(requestData), MeetingRequestResult::class.java)
        MatcherAssert.assertThat(result.status, Matchers.`is`("Booked"))
        MatcherAssert.assertThat(result.acceptedBy, Matchers.`is`(setOf(alice, bob, charlie)))

        // And the result should have been persisted for all participants
        val transactionId = result.txId

        val charlieQueryNode = simulator.createVirtualNode(alice, MeetingQueryFlow::class.java)
        val queryRequestData = RequestData.create(
            "r1",
            MeetingQueryFlow::class.java,
            MeetingQuery(transactionId)
        )

        val queryResponse = JsonMarshallingServiceFactory.create().parse(
            charlieQueryNode.callFlow(queryRequestData),
            MeeetingQueryResponse::class.java
        )
        MatcherAssert.assertThat(queryResponse.participants, Matchers.`is`(setOf(alice, bob, charlie)))
        MatcherAssert.assertThat(queryResponse.meetingDetail, Matchers.`is`(meetingDetail))
    }

    private fun createMember(commonName: String, orgUnit: String = "ExampleUnit", org: String = "ExampleOrg") : MemberX500Name =
        MemberX500Name.parse("CN=$commonName, OU=$orgUnit, O=$org, L=London, C=GB")

//    class LocalDateTimeSerializer: JsonSerializer<LocalDateTime>, JsonDeserializer<LocalDateTime> {
//        override fun serialize(item: LocalDateTime, jsonWriter: JsonWriter) {
//            val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss")
//            jsonWriter.writeString(formatter.format(item))
//        }
//
//        override fun deserialize(jsonRoot: JsonNodeReader): LocalDateTime {
//            return LocalDateTime.parse(jsonRoot.asText())
//        }
//    }
}