/*
 * Copyright (c) 2011-2015 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.io.net.http

import reactor.Environment
import reactor.io.codec.StandardCodecs
import reactor.io.net.NetStreams
import reactor.rx.Streams
import spock.lang.Specification

import java.util.concurrent.TimeUnit

/**
 * @author Stephane Maldini
 */
class HttpSpec extends Specification {

	static final int port = 8080
	Environment env

	def setup() {
		env = Environment.initializeIfEmpty()
	}

	def "http responds to requests from clients"() {
		given: "a simple HttpServer"

		  //Listen on localhost using default impl (Netty) and assign a global codec to receive/reply String data
			def server = NetStreams.httpServer {
				it.codec(StandardCodecs.STRING_CODEC).listen(port).dispatcher(Environment.sharedDispatcher())
			}

		  //Prepare a client using default impl (Netty) to connect on http://localhost:port/ and assign global codec to send/receive String data
			def client = NetStreams.httpClient {
				it.codec(StandardCodecs.STRING_CODEC).connect("localhost", port).dispatcher(Environment.sharedDispatcher())
			}

		when: "the server is prepared"

		  //prepare post request consumer on /test/* and capture the URL parameter "param"
			server.post('/test/{param}') { req ->

				//log then transform then log received http request content from the request body and the resolved URL parameter "param"
				//the returned stream is bound to the request stream and will auto read/close accordingly
				req
						.log('server-received')
						.map { it + ' ' + req.param('param') + '!' }
						.log('server-reply')

			}

		then: "the server was started"
			server?.start()?.awaitSuccess(5, TimeUnit.SECONDS)

		when: "data is sent with Reactor HTTP support"

		  //prepare an http post request-reply flow
			def content = client.post('/test/World') { req ->
				//prepare content-type
				req.header('Content-Type', 'text/plain')

				//return a producing stream to send some data along the request
				Streams
						.just("Hello")
						.log('client-send')

			}.flatMap { replies ->
				//successful request, listen for the first returned next reply and pass it downstream
				replies
						.log('client-received')
						.next()
			}.onError{
				//something failed during the request or the reply processing
				println "Failed requesting server: $it"
			}



		then: "data was recieved"
			//execute the request now
			client.open().awaitSuccess()

			//the produced reply should be there soon
			content.await() == "Hello World!"

		cleanup: "the client/server where stopped"
		//note how we order first the client then the server shutdown
			client?.close()?.flatMap { server.shutdown() }?.awaitSuccess(5, TimeUnit.SECONDS)
	}

}