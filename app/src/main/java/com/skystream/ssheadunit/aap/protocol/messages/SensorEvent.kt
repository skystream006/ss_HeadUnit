package com.skystream.ssheadunit.aap.protocol.messages

import com.skystream.ssheadunit.aap.AapMessage
import com.skystream.ssheadunit.aap.protocol.Channel
import com.skystream.ssheadunit.aap.protocol.proto.Sensors
import com.google.protobuf.Message

open class SensorEvent(val sensorType: Int, proto: Message)
    : AapMessage(Channel.ID_SEN, Sensors.SensorsMsgType.SENSOR_EVENT_VALUE, proto)
