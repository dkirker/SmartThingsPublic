/*
 *  Copyright 2017 - 2018 Stelpro
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *	  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Stelpro Ki Thermostat
 *
 *  Author: Stelpro
 *
 *  Date: 2018-04-24
 */
import physicalgraph.zwave.commands

metadata {
	definition (name: "Stelpro Ki Thermostat", namespace: "stelpro", author: "Stelpro", ocfDeviceType: "oic.d.thermostat") {
		capability "Actuator"
		capability "Temperature Measurement"
		capability "Temperature Alarm"
		capability "Thermostat"
		capability "Thermostat Mode"
		capability "Thermostat Operating State"
		capability "Thermostat Heating Setpoint"
		capability "Configuration"
		capability "Polling"
		capability "Sensor"
		capability "Refresh"
		capability "Health Check"

		// Right now this can disrupt device health if the device is currently offline -- it would be erroneously marked online.
		//attribute "outsideTemp", "number"

		//command "cycleModes" // Removing after testing
		command "quickSetHeat"
		command "quickSetOutTemp"
		command "increaseHeatSetpoint"
		command "decreaseHeatSetpoint"
		command "eco" // Command does not exist in "Thermostat Operating State"
		command "updateWeather"

		fingerprint deviceId: "0x0806", inClusters: "0x5E,0x86,0x72,0x40,0x43,0x31,0x85,0x59,0x5A,0x73,0x20,0x42", mfr: "0239", prod: "0001", model: "0001", deviceJoinName: "Stelpro Ki Thermostat"
	}

	// simulator metadata
	simulator { }

	preferences {
		input("heatdetails", "enum", title: "Do you want a detailed operating state notification?", options: ["No", "Yes"], defaultValue: "No", required: true, displayDuringSetup: true)
		input("zipcode", "text", title: "ZipCode (Outdoor Temperature)", description: "[Do not use space](Blank = No Forecast)")
	}

	tiles(scale : 2) {
		multiAttributeTile(name:"thermostatMulti", type:"thermostat", width:6, height:4, canChangeIcon: true) {
			tileAttribute("device.temperature", key: "PRIMARY_CONTROL") {
				attributeState("temperature", label:'${currentValue}°')
			}
			tileAttribute("device.heatingSetpoint", key: "VALUE_CONTROL") {
				attributeState("VALUE_UP", action: "increaseHeatSetpoint")
				attributeState("VALUE_DOWN", action: "decreaseHeatSetpoint")
			}
			tileAttribute("device.thermostatOperatingState", key: "OPERATING_STATE") {
				attributeState("idle", backgroundColor:"#44b621")
				attributeState("heating", backgroundColor:"#ffa81e")
			}
			tileAttribute("device.thermostatMode", key: "THERMOSTAT_MODE") {
				attributeState("heat", label:'${name}')
				attributeState("eco", label:'${name}')
			}
			tileAttribute("device.heatingSetpoint", key: "HEATING_SETPOINT") {
				attributeState("heatingSetpoint", label:'${currentValue}°')
			}
		}
		standardTile("mode", "device.thermostatMode", width: 2, height: 2) {
			state "heat", label:'${name}', action:"eco", nextState:"eco", icon:"http://cdn.device-icons.smartthings.com/Home/home29-icn@2x.png"
			state "eco", label:'${name}', action:"heat", nextState:"heat", icon:"http://cdn.device-icons.smartthings.com/Outdoor/outdoor3-icn@2x.png"
		}
		valueTile("heatingSetpoint", "device.heatingSetpoint", width: 2, height: 2) {
			state "heatingSetpoint", label:'Setpoint ${currentValue}°', backgroundColors:[
					[value: 31, color: "#153591"],
					[value: 44, color: "#1e9cbb"],
					[value: 59, color: "#90d2a7"],
					[value: 74, color: "#44b621"],
					[value: 84, color: "#f1d801"],
					[value: 95, color: "#d04e00"],
					[value: 96, color: "#bc2323"]
			]
		}
		standardTile("refresh", "device.refresh", decoration: "flat", width: 2, height: 2) {
			state "default", action:"refresh.refresh", icon:"st.secondary.refresh"
		}

		main ("thermostatMulti")
		details(["thermostatMulti", "mode", "heatingSetpoint", "refresh"])
	}
}

def getSupportedThermostatModes() {
	modes()
}

def getMinSetpointIndex() {
	0
}
def getMaxSetpointIndex() {
	1
}
def getThermostatSetpointRange() {
	(getTemperatureScale() == "C") ? [5, 30] : [41, 86]
}
def getHeatingSetpointRange() {
	thermostatSetpointRange
}

def getSetpointStep() {
	(getTemperatureScale() == "C") ? 0.5 : 1.0
}

def setupHealthCheck() {
	// Device-Watch simply pings if no device events received for 32min(checkInterval)
	sendEvent(name: "checkInterval", value: 2 * 15 * 60 + 2 * 60, displayed: false, data: [protocol: "zwave", hubHardwareId: device.hub.hardwareID])
}

def configureSupportedRanges() {
	sendEvent(name: "supportedThermostatModes", value: supportedThermostatModes, displayed: false)
	sendEvent(name: "thermostatSetpointRange", value: thermostatSetpointRange, displayed: false)
	sendEvent(name: "heatingSetpointRange", value: heatingSetpointRange, displayed: false)
}

def installed() {
	setupHealthCheck()

	configureSupportedRanges()
}

def updated() {
	setupHealthCheck()

	configureSupportedRanges()

	unschedule(scheduledUpdateWeather)
	if (settings.zipcode) {
		runEvery1Hour(scheduledUpdateWeather)
		scheduledUpdateWeather()
	}
}

def parse(String description) {
	if (description == "updated") {
		return null
	}

	// Class, version
	def map = createEvent(zwaveEvent(zwave.parse(description, [0x40:2, 0x43:2, 0x31:3, 0x42:1, 0x20:1, 0x85: 2])))
	if (!map) {
		return null
	}

	def result = [map]
	if (map.isStateChange && map.name in ["heatingSetpoint","thermostatMode"]) {
		def map2 = [
			name: "thermostatSetpoint",
			unit: getTemperatureScale(),
			data: [thermostatSetpointRange: thermostatSetpointRange]
		]
		if (map.name == "thermostatMode") {
			state.lastTriedMode = map.value
			map2.value = device.latestValue("heatingSetpoint")
		}
		else {
			def mode = device.latestValue("thermostatMode")
			log.debug "THERMOSTAT, latest mode = ${mode}"
			if (map.name == "heatingSetpoint") {
				map2.value = map.value
				map2.unit = map.unit
			}
		}
		if (map2.value != null) {
			log.debug "THERMOSTAT, adding setpoint event: $map"
			result << createEvent(map2)
		}
	}
	log.debug "Parse returned $result"
	result
}

def updateWeather() {
	log.debug "updating weather"
	def weather
	// If there is a zipcode defined, weather forecast will be sent. Otherwise, no weather forecast.
	if (settings.zipcode) {
		log.debug "ZipCode: ${settings.zipcode}"
		weather = getWeatherFeature( "conditions", settings.zipcode )

		// Check if the variable is populated, otherwise return.
		if (!weather) {
			log.debug( "Something went wrong, no data found." )
			return false
		}

		def tempToSend

		if(getTemperatureScale() == "C" ) {
			tempToSend = weather.current_observation.temp_c
			log.debug( "Outdoor Temperature: ${tempToSend} C" )
		}
		else {
			tempToSend = weather.current_observation.temp_f
			log.debug( "Outdoor Temperature: ${tempToSend} F" )
		}
		// Right now this can disrupt device health if the device is
		// currently offline -- it would be erroneously marked online.
		//sendEvent( name: 'outsideTemp', value: tempToSend )
		quickSetOutTemp(tempToSend)
	}
}

def scheduledUpdateWeather() {
	def actions = updateWeather()

	if (actions) {
		sendHubCommand(actions)
	}
}

// Command Implementations

/**
 * PING is used by Device-Watch in attempt to reach the Device
 **/
def ping() {
	zwave.sensorMultilevelV3.sensorMultilevelGet().format()
}

def poll() {
	delayBetween([
			updateWeather(),
			zwave.thermostatOperatingStateV1.thermostatOperatingStateGet().format(),
			zwave.thermostatModeV2.thermostatModeGet().format(),
			zwave.thermostatSetpointV2.thermostatSetpointGet(setpointType: 1).format(),
			zwave.sensorMultilevelV3.sensorMultilevelGet().format() // current temperature
		], 100)
}

// Event Generation
def zwaveEvent(thermostatsetpointv2.ThermostatSetpointReport cmd) {
	def cmdScale = cmd.scale == 1 ? "F" : "C"
	def temp;
	float tempfloat;
	def map = [:]

	if (cmd.scaledValue >= 327 ||
		cmd.setpointType != thermostatsetpointv2.ThermostatSetpointReport.SETPOINT_TYPE_HEATING_1) {
		return [:]
	}
	temp = convertTemperatureIfNeeded(cmd.scaledValue, cmdScale, cmd.precision)
	tempfloat = (Math.round(temp.toFloat() * 2)) / 2
	map.value = tempfloat

	map.unit = getTemperatureScale()
	map.displayed = false
	map.name = "heatingSetpoint"
	map.data = [heatingSetpointRange: heatingSetpointRange]

	// So we can respond with same format
	state.size = cmd.size
	state.scale = cmd.scale
	state.precision = cmd.precision

	map
}

def zwaveEvent(sensormultilevelv3.SensorMultilevelReport cmd) {
	def temp
	float tempfloat
	def format
	def map = [:]

	if (cmd.sensorType == sensormultilevelv3.SensorMultilevelReport.SENSOR_TYPE_TEMPERATURE_VERSION_1) {
		map.value = convertTemperatureIfNeeded(cmd.scaledSensorValue, cmd.scale == 1 ? "F" : "C", cmd.precision)
		map.unit = getTemperatureScale()
		map.name = "temperature"

		temp = map.value
		// The specific values checked below represent ambient temperature alarm indicators
		if (temp == "32765") {			// 0x7FFD
			map.name = "temperatureAlarm"
			map.value = "freeze"
			map.unit = ""
		}
		else if (temp == "32767") {		// 0x7FFF
			map.name = "temperatureAlarm"
			map.value = "heat"
			map.unit = ""
		}
		else if (temp == "-32768"){		// 0x8000
			map.name = "temperatureAlarm"
			map.value = "cleared"
			map.unit = ""
		}
		else {
			tempfloat = (Math.round(temp.toFloat() * 2)) / 2
			map.value = tempfloat
		}
	}
	else if (cmd.sensorType == sensormultilevelv3.SensorMultilevelReport.SENSOR_TYPE_RELATIVE_HUMIDITY_VERSION_2) {
		map.value = cmd.scaledSensorValue
		map.unit = "%"
		map.name = "humidity"
	}

	map
}

def zwaveEvent(thermostatoperatingstatev1.ThermostatOperatingStateReport cmd) {
	def map = [:]

	if (stateNameMap.containsKey(cmd.operatingState)) {
		map.name = "thermostatOperatingState"
		map.value = stateNameMap[cmd.operatingState]

		if (settings.heatdetails == "No") {
			map.displayed = false
		}
	} else {
		log.trace "${device.displayName} sent invalid operating state $value"
	}

	map
}

def zwaveEvent(thermostatmodev2.ThermostatModeReport cmd) {
	def map = [:]

	if (modeNameMap.containsKey(cmd.mode)) {
		map.name = "thermostatMode"
		map.value = modeNameMap[cmd.mode]
		map.data = [supportedThermostatModes: supportedThermostatModes]
	} else {
		log.trace "${device.displayName} sent invalid mode $value"
	}

	map
}

def zwaveEvent(associationv2.AssociationReport cmd) {
	delayBetween([
		zwave.associationV1.associationRemove(groupingIdentifier:1, nodeId:0).format(),
		zwave.associationV1.associationSet(groupingIdentifier:1, nodeId:[zwaveHubNodeId]).format(),
		poll()
	], 2300)
}

def zwaveEvent(thermostatmodev2.ThermostatModeSupportedReport cmd) {
	log.debug "Zwave event received: $cmd"
}

def zwaveEvent(basicv1.BasicReport cmd) {
	log.debug "Zwave event received: $cmd"
}

def zwaveEvent(physicalgraph.zwave.Command cmd) {
	log.warn "Unexpected zwave command $cmd"
}

def refresh() {
	poll()
}

def configure() {
	unschedule(scheduledUpdateWeather)
	if (settings.zipcode) {
		runEvery1Hour(scheduledUpdateWeather)
	}
	poll()
}

def quickSetHeat(degrees) {
	setHeatingSetpoint(degrees, 0)
}

def setHeatingSetpoint(preciseDegrees, delay = 0) {
	def degrees = new BigDecimal(preciseDegrees).setScale(1, BigDecimal.ROUND_HALF_UP)
	log.trace "setHeatingSetpoint($degrees, $delay)"
	def deviceScale = state.scale ?: 1
	def deviceScaleString = deviceScale == 2 ? "C" : "F"
	def locationScale = getTemperatureScale()
	def p = (state.precision == null) ? 1 : state.precision
	def setpointType = thermostatsetpointv2.ThermostatSetpointReport.SETPOINT_TYPE_HEATING_1

	def convertedDegrees
	if (locationScale == "C" && deviceScaleString == "F") {
		convertedDegrees = celsiusToFahrenheit(degrees)
	}
	else if (locationScale == "F" && deviceScaleString == "C") {
		convertedDegrees = fahrenheitToCelsius(degrees)
	}
	else {
		convertedDegrees = degrees
	}

	sendEvent(name: "heatingSetpoint", value: degrees, unit: locationScale, data: [heatingSetpointRange: heatingSetpointRange])
	sendEvent(name: "thermostatSetpoint", value: degrees, unit: locationScale, data: [thermostatSetpointRange: thermostatSetpointRange])

	delayBetween([
		zwave.thermostatSetpointV2.thermostatSetpointSet(setpointType: setpointType, scale: deviceScale, precision: p, scaledValue: convertedDegrees).format(),
		zwave.thermostatSetpointV2.thermostatSetpointGet(setpointType: setpointType).format(),
		poll()
	], 1000)
}

def quickSetOutTemp(degrees) {
	setOutdoorTemperature(degrees, 0)
}

def setOutdoorTemperature(outsideTemp, delay = 0) {
	def degrees = outsideTemp as Double
	def locationScale = getTemperatureScale()
	def p = (state.precision == null) ? 1 : state.precision
	def deviceScale = (locationScale == "C") ? 0 : 1
	def sensorType = sensormultilevelv3.SensorMultilevelReport.SENSOR_TYPE_TEMPERATURE_VERSION_1

	log.debug "setOutdoorTemperature: ${degrees}"
	zwave.sensorMultilevelV3.sensorMultilevelReport(sensorType: sensorType, scale: deviceScale, precision: p, scaledSensorValue: degrees).format()
}

def increaseHeatSetpoint() {
	float currentSetpoint = device.currentValue("heatingSetpoint")
	def locationScale = getTemperatureScale()
	float maxSetpoint = thermostatSetpointRange[maxSetpointIndex]

	if (currentSetpoint < maxSetpoint) {
		currentSetpoint = currentSetpoint + setpointStep
		quickSetHeat(currentSetpoint)
	}
}

def decreaseHeatSetpoint() {
	float currentSetpoint = device.currentValue("heatingSetpoint")
	def locationScale = getTemperatureScale()
	float minSetpoint = thermostatSetpointRange[minSetpointIndex]

	if (currentSetpoint > minSetpoint) {
		currentSetpoint = currentSetpoint - setpointStep
		quickSetHeat(currentSetpoint)
	}
}

// Testing before fully removing
/*def cycleModes() {
	def currentMode = device.currentState("thermostatMode")?.value
	def lastTriedMode = state.lastTriedMode ?: currentMode ?: "heat"
	def modeOrder = modes()
	def next = { modeOrder[modeOrder.indexOf(it) + 1] ?: modeOrder[0] }
	def nextMode = next(lastTriedMode)
	state.lastTriedMode = nextMode
	delayBetween([
			zwave.thermostatModeV2.thermostatModeSet(mode: modeNumericMap[nextMode]).format(),
			zwave.thermostatModeV2.thermostatModeGet().format()
		], 1000)
}*/

def modes() {
	["heat", "eco"]
}

def getModeNumericMap() {[
		"heat": thermostatmodev2.ThermostatModeReport.MODE_HEAT,
		"eco": thermostatmodev2.ThermostatModeReport.MODE_ENERGY_SAVE_HEAT
]}
def getModeNameMap() {[
	thermostatmodev2.ThermostatModeReport.MODE_HEAT: "heat",
	thermostatmodev2.ThermostatModeReport.MODE_ENERGY_SAVE_HEAT: "eco"
]}
def getStateNumericMap() {[
	"idle": thermostatoperatingstatev1.ThermostatOperatingStateReport.OPERATING_STATE_IDLE,
	"heating": thermostatoperatingstatev1.ThermostatOperatingStateReport.OPERATING_STATE_HEATING
]}
def getStateNameMap() {[
	thermostatoperatingstatev1.ThermostatOperatingStateReport.OPERATING_STATE_IDLE: "idle",
	thermostatoperatingstatev1.ThermostatOperatingStateReport.OPERATING_STATE_HEATING: "heating"
]}

def setCoolingSetpoint(coolingSetpoint) {
	log.trace "${device.displayName} does not support cool setpoint"
}

def heat() {
	log.trace "heat mode applied"
	delayBetween([
		zwave.thermostatModeV2.thermostatModeSet(mode: thermostatmodev2.ThermostatModeReport.MODE_HEAT).format(),
		zwave.thermostatModeV2.thermostatModeGet().format()
	], 1000)
}

def eco() {
	log.trace "eco mode applied"
	delayBetween([
		zwave.thermostatModeV2.thermostatModeSet(mode: thermostatmodev2.ThermostatModeReport.MODE_ENERGY_SAVE_HEAT).format(),
		zwave.thermostatModeV2.thermostatModeGet().format()
	], 1000)
}

def off() {
	log.trace "${device.displayName} does not support off mode"
}

def auto() {
	log.trace "${device.displayName} does not support auto mode"
}

def emergencyHeat() {
	log.trace "${device.displayName} does not support emergency heat mode"
}

def cool() {
	log.trace "${device.displayName} does not support cool mode"
}

def setThermostatMode(value) {
	if (modeNumericMap.containsKey(value)) {
		delayBetween([
			zwave.thermostatModeV2.thermostatModeSet(mode: modeNumericMap[value]).format(),
			zwave.thermostatModeV2.thermostatModeGet().format()
		], 1000)
	} else {
		log.trace "${device.displayName} does not support $value mode"
	}
}

def fanOn() {
	log.trace "${device.displayName} does not support fan on"
}

def fanAuto() {
	log.trace "${device.displayName} does not support fan auto"
}

def fanCirculate() {
	log.trace "${device.displayName} does not support fan circulate"
}

def setThermostatFanMode() {
	log.trace "${device.displayName} does not support fan mode"
}
