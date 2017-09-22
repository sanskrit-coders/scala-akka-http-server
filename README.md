# Introduction
A REST API server written with the Scala Akka infrastructure.

# Running
- Clone this git repo and use `sbt run` to start the Akka Http server.
- Create or edit the src/resources/server_config_local.json file based on src/resources/server_config_template.json  
- Test using http://petstore.swagger.io/ and replace the swagger.json with http://localhost:9090/api-docs/swagger.json . The Swagger UI can be used to send sample requests.

# Development and contribution
## Implementation decisions
- We use the Akka Http server infrastructure.
  - [Comparison of scala servers](https://blog.knoldus.com/2017/06/12/akka-http-vs-other-rest-api-tools/). 

## References
- We started off with the Akka Start example [here](https://github.com/pjfanning/swagger-akka-http-sample).