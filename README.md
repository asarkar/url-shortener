# url-shortener
[![Build Status](https://travis-ci.org/asarkar/url-shortener.svg?branch=master)](https://travis-ci.org/asarkar/url-shortener)

A microservice for shortening a given URL. Inspired by [this](https://www.youtube.com/watch?v=JQDHz72OA3c) system design YouTube video.

## Design Highlights

* Generates a conflict-free unique id for each URL by using [Consul sessions](https://www.consul.io/docs/internals/sessions.html).
  Each instance of the app is allocated a unique range of integers, each of which is converted to Base-62 and used as
  the identifier for saving the URL in Cassandra. Once a range is allocated to an instance, it is never reused even if
  the instance dies unexpectedly. This allows for horizontal scaling and fault tolerance. Currently, ranges are only
  allocated at application startup, but it's not too difficult to renew the ranges when they are about to be used up.
* Handles requests in a non-blocking manner.
* Micronaut aims to use reflection minimally, and enable low memory-footprint microservices.
* In spite of having various distributed dependencies, the app is able to handle failures even at startup.
* Consul was chosen over Zookeeper due to its cleaner design and better performance. See the [references](#references) 
  for one such benchmarking.
* Cassandra was chosen due to its ability to handle lots of writes.
* Redis was chosen due to its high availability and performance.
* Runs end-to-end integration tests by spinning up dependent Docker containers; skips the tests if Docker is not
  running.

Of course, to build a Production-grade system, a lot more thought would need to go into this design. Even though the
chances of collision in Consul is less, it's still a constraint for the system, and we wouldn't want a bunch of
instances competing for a lock. We will also want to ensure that the data is uniformly partitioned in Cassandra. 
Lastly, failure cases need to be thought through more clearly; what will happen if an instance exhausts its list of 
ids but Consul goes down? Or Cassandra fails?

This topic warrants a blog post of its own.

## Technologies Used

* [Micronaut](https://docs.micronaut.io/latest/guide/index.html) - base framework.
* [Consul](https://www.consul.io/) - service discovery and distributed configuration.
* [Apache Cassandra](http://cassandra.apache.org/) - database.
* [Redis](https://redis.io/) - distributed caching.

## Running Locally

The easiest way to run the app is using Docker Compose.

```
$ ./gradlew clean assemble && \
  docker-compose up --build --remove-orphans --no-recreate

$ short=$(curl -sS -X PUT -H "Content-Type: text/plain" "http://localhost:8080" -d "http://localhost:8080/test")

$ curl -G http://localhost:8080 --data-urlencode "url=$short"
```

## References

* [Designing a Cassandra Data Model](https://shermandigital.com/blog/designing-a-cassandra-data-model/)
* [From ZooKeeper to Consul](https://dadi.cloud/en/knowledge/network/from-zookeeper-to-consul/)