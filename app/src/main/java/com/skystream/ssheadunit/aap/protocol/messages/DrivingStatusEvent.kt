package com.skystream.ssheadunit.aap.protocol.messages

import com.skystream.ssheadunit.aap.AapMessage
import com.skystream.ssheadunit.aap.protocol.Channel
import com.skystream.ssheadunit.aap.protocol.proto.Sensors
import com.google.protobuf.Message

class DrivingStatusEvent(status: Sensors.SensorBatch.DrivingStatusData.Status)
    : AapMessage(Channel.ID_SEN, Sensors.SensorsMsgType.SENSOR_EVENT_VALUE, makeProto(status)) {

    companion object {
        private fun makeProto(status: Sensors.SensorBatch.DrivingStatusData.Status): Message {
            return Sensors.SensorBatch.newBuilder()
                    .addDrivingStatus(Sensors.SensorBatch.DrivingStatusData.newBuilder()
                            .setStatus(status.number))
                    .build()
        }
    }
}
