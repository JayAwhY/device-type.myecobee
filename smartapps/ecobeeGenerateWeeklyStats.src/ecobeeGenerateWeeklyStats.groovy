/**
 *  ecobeeGenerateWeeklyStats
 *
 *  Copyright 2015 Yves Racine
 *  LinkedIn profile: ca.linkedin.com/pub/yves-racine-m-sc-a/0/406/4b/
 *
 *  Developer retains all right, title, copyright, and interest, including all copyright, patent rights, trade secret 
 *  in the Background technology. May be subject to consulting fees under the Agreement between the Developer and the Customer. 
 *  Developer grants a non exclusive perpetual license to use the Background technology in the Software developed for and delivered 
 *  to Customer under this Agreement. However, the Customer shall make no commercial use of the Background technology without
 *  Developer's written consent.
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 *
 *  Software Distribution is restricted and shall be done only with Developer's written approval.
 * 
 *  N.B. Requires MyEcobee device (v5.0 and higher) available at 
 *          http://www.ecomatiqhomes.com/#!store/tc3yr 
 */
import java.text.SimpleDateFormat 
definition(
    name: "${get_APP_NAME()}",
    namespace: "yracine",
    author: "Yves Racine",
    description: "This smartapp allows a ST user to generate weekly runtime stats (by scheduling or based on custom dates) on their devices controlled by ecobee such as a heating & cooling component,fan, dehumidifier/humidifier/HRV/ERV. ",
    category: "My Apps",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/Partner/ecobee.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Partner/ecobee@2x.png"
)

preferences {
	section("About") {
		paragraph "${get_APP_NAME()}, the smartapp that generates weekly runtime reports about your ecobee components"
		paragraph "Version 1.2" 
		paragraph "If you like this smartapp, please support the developer via PayPal and click on the Paypal link below " 
			href url: "https://www.paypal.com/cgi-bin/webscr?cmd=_donations&business=yracine%40yahoo%2ecom&lc=US&item_name=Maisons%20ecomatiq&no_note=0&currency_code=USD&bn=PP%2dDonationsBF%3abtn_donateCC_LG%2egif%3aNonHostedGuest",
				title:"Paypal donation..."
		paragraph "Copyright©2016 Yves Racine"
			href url:"http://github.com/yracine/device-type.myecobee", style:"embedded", required:false, title:"More information..."  
				description: "http://github.com/yracine/device-type.myecobee/blob/master/README.md"
	}

	section("Generate weekly stats for this ecobee thermostat") {
		input "ecobee", "device.myEcobeeDevice", title: "Ecobee?"

	}
	section("Date for the initial run = YYYY-MM-DD") {
		input "givenEndDate", "text", title: "End Date [default=today]", required: false
	}        
	section("Time for the initial run (24HR)" ) {
		input "givenEndTime", "text", title: "End time [default=00:00]", required: false
	}        
	section( "Notifications" ) {
		input "sendPushMessage", "enum", title: "Send a push notification?", metadata:[values:["Yes", "No"]], required: false
		input "phoneNumber", "phone", title: "Send a text message?", required: false
    }
	section("Detailed Notifications") {
		input "detailedNotif", "bool", title: "Detailed Notifications?", required:false
    }
	section("Enable Amazon Echo/Ask Alexa Notifications [optional, default=false]") {
		input (name:"askAlexaFlag", title: "Ask Alexa verbal Notifications?", type:"bool",
			description:"optional",required:false)
	}
     
}

def installed() {
	log.debug "Installed with settings: ${settings}"

	initialize()
}

def updated() {
	log.debug "Updated with settings: ${settings}"

	unsubscribe()
	unschedule()    
	initialize()
}

def initialize() {


	state?.timestamp=''
	state?.componentAlreadyProcessed=''

	runIn((1*60),	"generateStats") // run 1 minute later as it requires notification.     
	subscribe(app, appTouch)
	state?.poll = [ last: 0, rescheduled: now() ]

	//Subscribe to different events (ex. sunrise and sunset events) to trigger rescheduling if needed
	subscribe(location, "sunset", rescheduleIfNeeded)
	subscribe(location, "mode", rescheduleIfNeeded)
	subscribe(location, "sunsetTime", rescheduleIfNeeded)

	rescheduleIfNeeded()   
}


def rescheduleIfNeeded(evt) {
	if (evt) log.debug("rescheduleIfNeeded>$evt.name=$evt.value")
	Integer delay = (24*60) // By default, do it every day
	BigDecimal currentTime = now()    
	BigDecimal lastPollTime = (currentTime - (state?.poll["last"]?:0))  
 
	if (lastPollTime != currentTime) {    
		Double lastPollTimeInMinutes = (lastPollTime/60000).toDouble().round(1)      
		log.info "rescheduleIfNeeded>last poll was  ${lastPollTimeInMinutes.toString()} minutes ago"
	}
	if (((state?.poll["last"]?:0) + (delay * 60000) < currentTime) && canSchedule()) {
		log.info "rescheduleIfNeeded>scheduling dailyRun in ${delay} minutes.."
//		generate the stats every day at 0:30 
		schedule("0 30 0 * * ?", dailyRun)    
	}
    
	// Update rescheduled state
    
	if (!evt) state.poll["rescheduled"] = now()
}
   

def appTouch(evt) {
	state?.timestamp=''
	state?.componentAlreadyProcessed=''
	generateStats()
}

void reRunIfNeeded() {
	if (detailedNotif) {    
		log.debug("reRunIfNeeded>About to call generateStats() with state.componentAlreadyProcessed=${state?.componentAlreadyProcessed}")
		send (">About to call generateStats() with state.componentAlreadyProcessed=${state?.componentAlreadyProcessed}")
	}    
	generateStats()
   
}


void dailyRun() {
	Integer delay = (24*60) // By default, do it every day
	state?.poll["last"] = now()
		
	//schedule the rescheduleIfNeeded() function
    
	if (((state?.poll["rescheduled"]?:0) + (delay * 60000)) < now()) {
		log.info "takeAction>scheduling rescheduleIfNeeded() in ${delay} minutes.."
		schedule("0 30 0 * * ?", rescheduleIfNeeded)    
		// Update rescheduled state
		state?.poll["rescheduled"] = now()
	}
	settings.givenEndDate=null
	settings.givenEndTime=null
	if (detailedNotif) {    
		log.debug("dailyRun>About to call generateStats() with settings.givenEndDate=${settings.givenEndDate}")
		send ("About to call generateStats() with settings.givenEndDate=${settings.givenEndDate}")
	}    
	generateStats()
    
}


private String formatISODateInLocalTime(dateInString, timezone='') {
	def myTimezone=(timezone)?TimeZone.getTimeZone(timezone):location.timeZone 
	if ((dateInString==null) || (dateInString.trim()=="")) {
		return (new Date().format("yyyy-MM-dd HH:mm:ss", myTimezone))
	}    
	SimpleDateFormat ISODateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
	Date ISODate = ISODateFormat.parse(dateInString)
	String dateInLocalTime =new Date(ISODate.getTime()).format("yyyy-MM-dd HH:mm:ss", myTimezone)
	log.debug("formatDateInLocalTime>dateInString=$dateInString, dateInLocalTime=$dateInLocalTime")    
	return dateInLocalTime
}


private def formatDate(dateString) {
	SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm zzz")
	Date aDate = sdf.parse(dateString)
	return aDate
}

private def get_nextComponentStats(component='') {
	if (detailedNotif) {
		log.debug "get_nextComponentStats>About to get ${component}'s next component from components table"
		send "get_nextComponentStats,About to get ${component}'s next component from components table"
	}

	def nextInLine=[:]

	def components = [
		'': 
			[position:1, next: 'auxHeat1'
			], 
		'auxHeat1': 
			[position:2, next: 'auxHeat2'
			], 
		'auxHeat2': 
			[position:3, next: 'auxHeat3'
			], 
		'auxHeat3': 
			[position:4, next: 'compCool2'
			], 
		'compCool2': 
			[position:5, next: 'compCool1'
			],
		'compCool1': 
			[position:6, next: 'done'
			], 
		]
	try {
		nextInLine = components.getAt(component)
	} catch (any) {
		nextInLine=[position:1, next:'auxHeat1']
		if (detailedNotif) {
			log.debug "get_nextComponentStats>${component} not found, nextInLine=${nextInLine}"
		}
	}          
	if (detailedNotif) {
		log.debug "get_nextComponentStats>got ${component}'s next component from components table= ${nextInLine}"
		send "get_nextComponentStats,got ${component}'s next component from components table= ${nextInLine}"
	}
	return nextInLine
		            
}


void generateStats() {
	String dateInLocalTime = new Date().format("yyyy-MM-dd", location.timeZone) 
	def MAX_POSITION=6    
	float runtimeTotalAvgWeekly
    
	def delay = 2 // 2-minute delay for rerun
    
	try {
		unschedule(reRunIfNeeded)
	} catch (e) {
    
		if (detailedNotif) {    
			log.debug("${get_APP_NAME()}>Exception $e while unscheduling reRunIfNeeded")
			send ("Exception $e while unscheduling reRunIfNeeded")
		}    	
	}  
	def component = state?.componentAlreadyProcessed   
	def nextComponent  = get_nextComponentStats(component) // get nextComponentToBeProcessed	
	if (detailedNotif) {    
		log.debug("${get_APP_NAME()}>About to process nextComponent=${nextComponent}, state.componentAlreadyProcessed=${state?.componentAlreadyProcessed}")
		send ("About to process nextComponent=${nextComponent}, state.componentAlreadyProcessed=${state?.componentAlreadyProcessed}")
	}    	
	if (state?.timestamp == dateInLocalTime && nextComponent.position >=MAX_POSITION) {
		return // the weekly stats are already generated 
	} else {    	
		// schedule a rerun till the stats are generated properly
		schedule("0 0/${delay} * * * ?", reRunIfNeeded)
	}    	
    
	String timezone = new Date().format("zzz", location.timeZone)
	String dateAtMidnight = dateInLocalTime + " 00:00 " + timezone    
	if (detailedNotif) {    
		log.debug("${get_APP_NAME()}>date at Midnight= ${dateAtMidnight}")
		send ("date at Midnight= ${dateAtMidnight}")
	}        
	Date endDate = formatDate(dateAtMidnight) 
            
	def givenEndDate = (settings.givenEndDate) ?: endDate.format("yyyy-MM-dd", location.timeZone) 
	def givenEndTime=(settings.givenEndTime) ?:"00:00"    
	def dateTime = givenEndDate + " " + givenEndTime + " " + timezone
	endDate = formatDate(dateTime)
	Date aWeekAgo= endDate -7   
	if (detailedNotif) {    
		log.debug("${get_APP_NAME()}>end dateTime = ${dateTime}, endDate in UTC =${endDate.format("yyyy-MM-dd HH:mm:ss", TimeZone.getTimeZone("UTC"))}")
		send ("end dateTime = ${dateTime}, endDate in UTC =${endDate.format("yyyy-MM-dd HH:mm:ss", TimeZone.getTimeZone("UTC"))}")
	}


	// Get the auxHeat1's runtime for startDate-endDate period
	component = 'auxHeat1'

	if (nextComponent.position <= 1) { 
		if (detailedNotif) {
			log.debug("${get_APP_NAME()}>For component ${component}, about to call generateRuntimeReport with aWeekAgo in UTC =${aWeekAgo.format("yyyy-MM-dd HH:mm:ss", TimeZone.getTimeZone("UTC"))}")
			send ("For component ${component}, about to call generateRuntimeReport with aWeekAgo in UTC =${aWeekAgo.format("yyyy-MM-dd HH:mm:ss", TimeZone.getTimeZone("UTC"))}")
		}        
		generateRuntimeReport(component,aWeekAgo, endDate,'weekly') // generate stats for the last 7 days
		runtimeTotalAvgWeekly = (ecobee.currentAuxHeat1RuntimeAvgWeekly)? ecobee.currentAuxHeat1RuntimeAvgWeekly.toFloat().round(2):0
		state?.componentAlreadyProcessed=component
		if (runtimeTotalAvgWeekly) {
			send ("generated ${component}'s average weekly runtime stats=${runtimeTotalAvgWeekly} minutes since ${String.format('%tF', aWeekAgo)}", settings.askAlexaFlag)
		}     
	}
	int heatStages = ecobee.currentHeatStages.toInteger()
    
    
	component = 'auxHeat2'
	if (heatStages >1 && nextComponent.position <= 2) { 
    
//	Get the auxHeat2's runtime for startDate-endDate period
 	
		if (detailedNotif) {
			log.debug("${get_APP_NAME()}>For component ${component}, about to call generateRuntimeReport with aWeekAgo in UTC =${aWeekAgo.format("yyyy-MM-dd HH:mm:ss", TimeZone.getTimeZone("UTC"))}")
			send ("For component ${component}, about to call generateRuntimeReport with aWeekAgo in UTC =${aWeekAgo.format("yyyy-MM-dd HH:mm:ss", TimeZone.getTimeZone("UTC"))}")
		}        
		generateRuntimeReport(component,aWeekAgo, endDate,'weekly') // generate stats for the last 7 days
		runtimeTotalAvgWeekly = (ecobee.currentAuxHeat2RuntimeAvgWeekly)? ecobee.currentAuxHeat2RuntimeAvgWeekly.toFloat().round(2):0
		state?.componentAlreadyProcessed=component
		if (runtimeTotalAvgWeekly) {
			send ("generated ${component}'s average weekly runtime stats=${runtimeTotalAvgWeekly} minutes since ${String.format('%tF', aWeekAgo)}", settings.askAlexaFlag)
		}     

	}     

	component = 'auxHeat3'
	if (heatStages >2 && nextComponent.position <= 3) { 
    
//	Get the auxHeat3's runtime for startDate-endDate period
 	
		if (detailedNotif) {
			log.debug("${get_APP_NAME()}>For component ${component}, about to call generateRuntimeReport with aWeekAgo in UTC =${aWeekAgo.format("yyyy-MM-dd HH:mm:ss", TimeZone.getTimeZone("UTC"))}")
			send ("For component ${component}, about to call generateRuntimeReport with aWeekAgo in UTC =${aWeekAgo.format("yyyy-MM-dd HH:mm:ss", TimeZone.getTimeZone("UTC"))}")
		}        
		generateRuntimeReport(component,aWeekAgo, endDate,'weekly') // generate stats for the last 7 days
		runtimeTotalAvgWeekly = (ecobee.currentAuxHeat3RuntimeAvgWeekly)? ecobee.currentAuxHeat3RuntimeAvgWeekly.toFloat().round(2):0
		state?.componentAlreadyProcessed=component
		if (runtimeTotalAvgWeekly) {
			send ("generated ${component}'s average weekly runtime stats=${runtimeTotalAvgWeekly} minutes since ${String.format('%tF', aWeekAgo)}", settings.askAlexaFlag)
		}     
	}     

// Get the compCool1's runtime for startDate-endDate period

	int coolStages = ecobee.currentCoolStages.toInteger()

	    
//	Get the compCool2's runtime for startDate-endDate period
	component = 'compCool2'

	if (coolStages >1 && nextComponent.position <= 4) {
		if (detailedNotif) {
			log.debug("${get_APP_NAME()}>For component ${component}, about to call generateRuntimeReport with aWeekAgo in UTC =${aWeekAgo.format("yyyy-MM-dd HH:mm:ss", TimeZone.getTimeZone("UTC"))}")
			send ("For component ${component}, about to call generateRuntimeReport with aWeekAgo in UTC =${aWeekAgo.format("yyyy-MM-dd HH:mm:ss", TimeZone.getTimeZone("UTC"))}")
		}        
		generateRuntimeReport(component,aWeekAgo, endDate,'weekly') // generate stats for the last 7 days
		runtimeTotalAvgWeekly = (ecobee.currentCompCool2RuntimeAvgWeekly)? ecobee.currentCompCool2RuntimeAvgWeekly.toFloat().round(2):0
		state?.componentAlreadyProcessed=component
		if (runtimeTotalAvgWeekly) {
			send ("generated ${component}'s average weekly runtime stats=${runtimeTotalAvgWeekly} minutes since ${String.format('%tF', aWeekAgo)}", settings.askAlexaFlag)
		}     
	} 
    
	component = 'compCool1'
	if (nextComponent.position <= 5) {
		if (detailedNotif) {
			log.debug("${get_APP_NAME()}>For component ${component}, about to call generateRuntimeReport with aWeekAgo in UTC =${aWeekAgo.format("yyyy-MM-dd HH:mm:ss", TimeZone.getTimeZone("UTC"))}")
			send ("For component ${component}, about to call generateRuntimeReport with aWeekAgo in UTC =${aWeekAgo.format("yyyy-MM-dd HH:mm:ss", TimeZone.getTimeZone("UTC"))}")
		}        
		generateRuntimeReport(component,aWeekAgo, endDate,'weekly') // generate stats for the last 7 days
		runtimeTotalAvgWeekly = (ecobee.currentCompCool1RuntimeAvgWeekly)? ecobee.currentCompCool1RuntimeAvgWeekly.toFloat().round(2):0
		state?.componentAlreadyProcessed=component
		if (runtimeTotalAvgWeekly) {
			send ("generated ${component}'s average weekly runtime stats=${runtimeTotalAvgWeekly} minutes since ${String.format('%tF', aWeekAgo)}", settings.askAlexaFlag)
		}     
	}        
    
	component= state?.componentAlreadyProcessed   
	nextComponent  = get_nextComponentStats(component) // get nextComponentToBeProcessed	
	if (nextComponent?.position >=MAX_POSITION) {
		send "generated all stats since ${String.format('%tF', aWeekAgo)}"
		unschedule(reRunIfNeeded) // No need to reschedule again as the stats are completed.
		state?.timestamp = dateInLocalTime // save the date to avoid re-execution.
	}

}

private void generateRuntimeReport(component, startDate, endDate, frequence='daily') {

//	if (detailedNotif) {
//		log.debug("generateRuntimeReport>For component ${component}, about to call getReportData with endDate in UTC =${endDate.format("yyyy-MM-dd HH:mm:ss", TimeZone.getTimeZone("UTC"))}")
//		send ("For component ${component}, about to call getReportData with aWeekAgo in UTC =${endDate.format("yyyy-MM-dd HH:mm:ss", TimeZone.getTimeZone("UTC"))}")
//	}        
	ecobee.getReportData("", startDate, endDate, null, null, component,false)
//	if (detailedNotif) {
//		log.debug("generateRuntimeReport>For component ${component}, about to call generateReportRuntimeEvents with endDate in UTC =${endDate.format("yyyy-MM-dd HH:mm:ss", TimeZone.getTimeZone("UTC"))}")
//		send ("For component ${component}, about to call generateReportRuntimeEvents with aWeekAgo in UTC =${endDate.format("yyyy-MM-dd HH:mm:ss", TimeZone.getTimeZone("UTC"))}")
//	}        
	ecobee.generateReportRuntimeEvents(component, startDate,endDate, 0, null,frequence)

}

private send(msg, askAlexa=false) {
	def message = "${get_APP_NAME()}>${msg}"

	if (sendPushMessage != "No") {
		
		if (askAlexa) {
			sendLocationEvent(name: "AskAlexaMsgQueue", value: "${get_APP_NAME()}", isStateChange: true, descriptionText: msg)        
		} else {        
			sendPush(message)
		}            
	}
	
	if (phone) {
		log.debug("sending text message")
		sendSms(phone, message)
	}
    
	log.debug msg
    
}

private def get_APP_NAME() {
	return "ecobeeGenerateWeeklyStats"
}
