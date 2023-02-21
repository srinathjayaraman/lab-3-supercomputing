# Lab 3 Report
## Usage

The application is compiled and run inside the transformer service container via ```sbt```. The argument passed alongside the ```run``` command is a number that specifies the time window in **seconds**. There is fixed a default time window of 10 seconds which is used **only** when the user does not provide a window of their own choosing. 
We are assigning a default value of 10 seconds to the ```timeWindow```. If no time window is specified while running the app, we notify the user that the default window is being used. This is done in the code block given below:

```scala
var timeWindow = defaultTime.timeWindow // This is the value of 10
  if (args.nonEmpty) {
    timeWindow = args(0).toInt
  } else {
    printf("Using default window of 10s \n")
  }
```

## Functional overview

As outlined in the guide to lab 3, our application filters check-ins by beer style, counts check-ins per city within a time window, and produces streaming updates for the visualizer. The main steps of our application are explained below:

### Read the input stream

We start by creating the case class so we can process the input records from the ```events``` stream in a type safe manner:

```scala

case class cityInput(
    timestamp: Long,
    city_id: Int,
    city_name: String,
    style: String
)

```

In order to get the deserialized to the desired case class, we added the line```import io.github.azhur.kafkaserdecirce.CirceSupport.toSerde``` to our code. This library allowed us to handle the JSON very easily, with just a few lines of code, shown below:

```scala
  val events: KStream[String, cityInput] =
    builder
      .stream[String, cityInput](
        "events"
      )
```

Now it is possible to work with the data in a type-safe manner.

### Filter the input stream

As mentioned before, now we need to just filter the input stream with the check-ins of the beer styles of our choice in the input file ```beer.styles```. In order to perform this we do the following:

```scala
  val lines = scala.io.Source.fromFile("./beer.styles").mkString.split("\r\n")
  ```
  
And then, havig the array of styles, we proceed to perform the **stateless** process of filtering, in which we only care about the incoming input, through the following code:
  
```scala
  events.filter((_, cityInput) => lines contains cityInput.style)
  ```

This way we are sure that only the styles we are looking for are included in the building stream.
  
### Transform the stream

The most important part of the flow is when we build the proper output. For this purpose, we could choose either a Processor or a Transformer.

A Processor works on all the records in a stream, one by one. It is stateless, but can be considered stateful if combined with a ```StateStore```. If not, it is stateless but access to ```ProcessorContext``` and the metadata of each record is retained. On the other hand, a Transformer will transform each record in the input stream into *zero* or *one record* in the output stream, allowing us to transform the input into an output. It also allows us to arbitarily modify the key and value types. 

In both cases, if we want to use a ```StateStore```, it must be added to the topology and connected to the Processor/Transformer. As we intend to take into account the previous check-ins during a window of time and we also need to give a different type of output, we have decided to go with a Transformer.

In order to make this aproach a **stateful** one (since we need to store the previous check-ins in the current window), it was necessary to create a ```StateSore``` and add it to the current builder as the shown below:

```scala
val storeBuilder = Stores.keyValueStoreBuilder(
    Stores.inMemoryKeyValueStore("cities_count"),
    Serdes.String,
    Serdes.String
  )
  builder.addStateStore(storeBuilder)
```

The idea of this ```stateStore``` is to store the info with the following structure:

```KeyValue <K, V>``` where ```K = "city_id"``` and ```V = "timestamp_1-timestamp_2-...-timestamp_n"```

Through this structure it was possible to have a count of the time and the number of events that we are storing at the moment.

Now that have our ```stateStore``` inside our ```builder```, we can proceed with creating our ```Transformer``` function. But first, an output case class is declared in order to model the output:

```scala
case class cityOutput(city_id: Int, count: Long)
```

And then the ```transform``` class with the following structure extends from the ```Transformer``` class:

```scala
class cityTransform(timeWindow: Int)
    extends Transformer[String, cityInput, KeyValue[String, cityOutput]] {
   
  var context: ProcessorContext = _
  var state: KeyValueStore[String, String] = _
    
  override def init(context: ProcessorContext): Unit = {
    ...
  }

  override def transform(
      key: String,
      value: cityInput
  ): KeyValue[String, cityOutput] = {
    ...
  }

  override def close(): Unit = ()

}
```

The most important parts of the Transformer are the ```init``` and the ```transform``` functions. The first one assigns the ```context```, ties the ```StateStore``` to the ```transformer```, and creates a scheduler that will perform a specific ```Punctuator()``` function within each defined interval. In order to get good performance the processing interval was fixed to 100ms and the type of punctuation was set to ```WALL_CLOCK_TIME``` in order to have a "movable" window that does not depend on the input stream. 

In order to take advantage of this function, we decided to use punctuation to check if the stored events are inside the window or not. For this purpose we iterated over the values of each stored state and if the ```timestamp_n``` compared to the input ```timestamp``` was outside the defined window, then we would not include it in the new list of events. The following code is the ```punctuate(timestamp: Long)``` function defined for this purpose:

```scala
override def punctuate(timestamp: Long): Unit = {
          val iter = state.all()
          while (iter.hasNext) {
            val pair = iter.next()
            val count_value = state.get(pair.key)
            if (count_value != null) {
              val events = count_value.split("-")
              var new_event = ""
              for (event <- events) {
                if (
                  event != "" && timestamp - event.toLong < (timeWindow * 1000)
                ) {
                  if (new_event == "") {
                    new_event += event
                  } else {
                    new_event += "-" + event
                  }
                }
              }
              state.put(pair.key, new_event)
              if (new_event == "") {
                context.forward(pair.key, new cityOutput(pair.key.toInt, 0))
              } else {
                context.forward(
                  pair.key,
                  new cityOutput(pair.key.toInt, pair.value.split("-").size)
                )
              }
            }
          }

        }
```

The other function inside the transformer that was executed each time a new event arrived was the ```transform()``` function, this one added the current ```timestamp``` to the list we had for a specific key of a previous registered city:

```scala
override def transform(
      key: String,
      value: cityInput
  ): KeyValue[String, cityOutput] = {
    val count_value = this.state.get(key)
    var ans = 0
    if (count_value != null && count_value != "") {
      val events = count_value.split("-")
      var new_event = value.timestamp.toString
      for (event <- events) {
        if (value.timestamp - event.toLong < (timeWindow * 1000)) {
          new_event += "-" + event
        }
      }
      this.state.put(key, new_event)
      ans = new_event.split("-").size
    } else {
      this.state.put(key, value.timestamp.toString)
      ans = 1
    }
    KeyValue.pair(
      key,
      new cityOutput(key.toInt, ans)
    )
  }
```
In order to apply all these transformations to the input stream it is necessary to apply the ```transformation``` function and make it explicit in the use of the ```storeBuilder```:

```scala
val outputs =
    events.transform(() => { new cityTransform(timeWindow) }, "cities_count")
```

With this functions, the stream now has an output of type ```cityOutput(Integer, Long)```.

### Publish to updates

The only remaining step is to now push the new stream into the builder with the ```update``` topic:

```scala
outputs.to("updates")
```

The remaining parts of the code were already provided and started the kafka streaming to the visualizer.

```
val streams: KafkaStreams = new KafkaStreams(builder.build(), props)
  streams.start()
  ```
## Kafka topology and setup

The topology of our solution is given below:

![kafka topology](https://github.com/abs-tudelft-sbd20/lab-3-group-29/blob/Workontransformer/kafka%20topology.png)

Our topology follows the structure that we were aiming for in order to get the data and publish it in the new format

### KSTREAM-SOURCE

We are reading the events stream and converting the input to a case class so it can be handled in a type-safe manner. This can be considered a **stateless** operation since we do not need to store the previous record.

### KSTREAM-FILTER

This part of the topology is also **stateless** because we are only processing the current record from the events stream, and we do not need to know or store the state of the previous record.

### KSTREAM-TRANSFORM

This is a **stateful** step because we need to know the number of previous events for a particular city/beer style combination as we need to perform a customized aggregate (count) of the records. This count is stored in the ```cities_count``` stateStore as shown in the Kafka topology above.

### KSTREAM-SINK

This is a **stateless** operation because at this stage we are just publishing the output to a KStream, and we do not need to know anything about previous records in order to publish the current one. It can be 

## Result

Now that we have our implementation, it is possible to see how it works  using the visualizer. For this purpose we started the composed docker, that had in it the necessary architecture and the required containers.

Printing the logs, we obtained the following sample output:

```
updates_1           | 316541	{"city_id":316541,"count":1}
updates_1           | 1668341	{"city_id":1668341,"count":2}
updates_1           | 1172451	{"city_id":1172451,"count":2}
updates_1           | 3875024	{"city_id":3875024,"count":1}
updates_1           | 2314302	{"city_id":2314302,"count":2}
updates_1           | 360995	{"city_id":360995,"count":1}
updates_1           | 3448439	{"city_id":3448439,"count":5}
  ```
  
Which is the required output for this lab. Screenshot of the visualizer is given below:

~[visualizer output](https://github.com/abs-tudelft-sbd20/lab-3-group-29/blob/Workontransformer/visualizer%20output.png)

We also felt the necessity to measure the time that the transformer and the punctuator took to do their processing, given that these had a lot of iterators in them and could bottleneck the performance. The time for the punctuator is around 43ms which is why we are using a default interval of 100ms and the time taken for the transformer is 0.005ms. The puncutator performance could be improved upon in future iterations.
