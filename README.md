# java-vtl-tools
A collection of tools for Java VTL.

## Testing the VTL-Pogues-Connector

* Make sure the simpson.xml (in the resource directory) is available for the connector.

## Build and run as docker image
Run "mvn clean install" to build the application itself.

Build Docker image: docker build . -t i3sessnet/java-vtl-tools:latest

Run the application: docker run -p 8080:8080 i3sessnet/java-vtl-tools:latest

## Access the demo-application:

After starting the application/container it can be accessed from [http://localhost:8080/demo/index.html#](http://localhost:8080/demo/index.html#)
