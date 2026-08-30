package com.rsplwe.esurfing.protocol

import com.rsplwe.esurfing.Constants
import com.rsplwe.esurfing.States
import com.rsplwe.esurfing.utils.getTime

/**
 * Builds the request XML bodies for the authentication lifecycle. The wire
 * shape is unchanged from the original client — only the construction site
 * moved here so [com.rsplwe.esurfing.Client] stays an orchestrator.
 */
object AuthPayloads {

    fun ticket(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <request>
            <user-agent>${Constants.USER_AGENT}</user-agent>
            <client-id>${States.clientId}</client-id>
            <local-time>${getTime()}</local-time>
            <host-name>${Constants.HOST_NAME}</host-name>
            <ipv4>${States.userIp}</ipv4>
            <ipv6></ipv6>
            <mac>${States.macAddress}</mac>
            <ostag>${Constants.HOST_NAME}</ostag>
            <gwip>${States.acIp}</gwip>
        </request>
    """.trimIndent()

    fun login(ticket: String, smsCode: String, user: String, password: String): String {
        val verify = if (smsCode.isBlank()) "" else "<verify>${smsCode}</verify>"
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <request>
                <user-agent>${Constants.USER_AGENT}</user-agent>
                <client-id>${States.clientId}</client-id>
                <ticket>$ticket</ticket>
                <local-time>${getTime()}</local-time>
                <userid>$user</userid>
                <passwd>$password</passwd>
                $verify
            </request>
        """.trimIndent()
    }

    fun heartbeat(ticket: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <request>
            <user-agent>${Constants.USER_AGENT}</user-agent>
            <client-id>${States.clientId}</client-id>
            <local-time>${getTime()}</local-time>
            <host-name>${Constants.HOST_NAME}</host-name>
            <ipv4>${States.userIp}</ipv4>
            <ticket>$ticket</ticket>
            <ipv6></ipv6>
            <mac>${States.macAddress}</mac>
            <ostag>${Constants.HOST_NAME}</ostag>
        </request>
    """.trimIndent()

    fun term(ticket: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <request>
            <user-agent>${Constants.USER_AGENT}</user-agent>
            <client-id>${States.clientId}</client-id>
            <local-time>${getTime()}</local-time>
            <host-name>${Constants.HOST_NAME}</host-name>
            <ipv4>${States.userIp}</ipv4>
            <ticket>$ticket</ticket>
            <ipv6></ipv6>
            <mac>${States.macAddress}</mac>
            <ostag>${Constants.HOST_NAME}</ostag>
        </request>
    """.trimIndent()
}