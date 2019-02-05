[![Build Status](https://travis-ci.org/vedavaapi/scala-akka-http-server.svg?branch=master)](https://travis-ci.org/vedavaapi/scala-akka-http-server)

## Introduction
A REST API server written with the Scala Akka infrastructure.

Significant services include:
- Sanskrit grammar interfaces
- Podcast generation

## Running
- Clone this git repo.
- Create or edit the src/resources/server_config_local.json file based on src/resources/server_config_template.json
- Use `sbt run` to start the Akka Http server.
- Test using http://petstore.swagger.io/ and replace the swagger.json with http://localhost:9090/api-docs/swagger.json . The Swagger UI can be used to send sample requests.

## Setting up as a service
- Set up service definitions: copy files in systemd directory.
- Set permissions and ownership: `sudo chown samskritam:dip . -R`
- Prod service
	- `sudo systemctl start scala-akka-http-server.service`
	- `sudo journalctl -u scala-akka-http-server.service`
- Test service
	- `sudo systemctl start scala-akka-http-server-test.service`
	- `sudo journalctl -u scala-akka-http-server-test.service -f`


## Development and contribution
### Implementation decisions
- We use the Akka Http server infrastructure.
  - [Comparison of scala servers](https://blog.knoldus.com/2017/06/12/akka-http-vs-other-rest-api-tools/). 

### References
- We started off with the Akka Start example [here](https://github.com/pjfanning/swagger-akka-http-sample).