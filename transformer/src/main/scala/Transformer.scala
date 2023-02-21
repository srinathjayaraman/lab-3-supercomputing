import java.time.{Duration, LocalDateTime}
import java.util.Properties

import io.github.azhur.kafkaserdecirce.CirceSupport.toSerde
import org.apache.kafka.streams.kstream.Transformer
import org.apache.kafka.streams.processor.{
  ProcessorContext,
  PunctuationType,
  Punctuator
}
import org.apache.kafka.streams.scala._
import org.apache.kafka.streams.scala.kstream._
import org.apache.kafka.streams.state.{KeyValueStore, Stores}
import org.apache.kafka.streams.{KafkaStreams, KeyValue, StreamsConfig}

import scala.language.postfixOps

object defaultIntervalTime {
  val timeWindow = 100
}

object defaultWindowTime {
  val timeWindow = 10
}

case class cityInput(
    timestamp: Long,
    city_id: Int,
    city_name: String,
    style: String
)

case class cityOutput(city_id: Int, count: Long)

class cityTransform(timeWindow: Int)
    extends Transformer[String, cityInput, KeyValue[String, cityOutput]] {

  var context: ProcessorContext = _
  var state: KeyValueStore[String, String] = _

  override def init(context: ProcessorContext): Unit = {
    this.context = context
    this.state = context
      .getStateStore("cities_count")
      .asInstanceOf[KeyValueStore[String, String]]
    context.schedule(
      Duration.ofMillis(defaultIntervalTime.timeWindow),
      PunctuationType.WALL_CLOCK_TIME,
      new Punctuator() {
        override def punctuate(timestamp: Long): Unit = {
          val startTime = System.nanoTime()
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
          val endTime = System.nanoTime()
//          printf("Punctuator time: " + (endTime-startTime) + "ns\n")
        }
      }
    );
  }

  override def transform(
      key: String,
      value: cityInput
  ): KeyValue[String, cityOutput] = {
    val startTime = System.nanoTime()
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

    val endTime = System.nanoTime()
    printf("Transformer time: " + (endTime - startTime) + "ns \n")
    KeyValue.pair(
      key,
      new cityOutput(key.toInt, ans)
    )
  }

  override def close(): Unit = ()

}

object Transformer_app extends App {
  import Serdes._
  import io.circe.generic.auto._
  import org.apache.kafka.streams.scala.ImplicitConversions._

  val props: Properties = {
    val p = new Properties()
    p.put(StreamsConfig.APPLICATION_ID_CONFIG, "transformer")
    p.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka-server:9092")
    p
  }

  var timeWindow = defaultWindowTime.timeWindow
  if (args.nonEmpty) {
    timeWindow = args(0).toInt
  } else {
    printf("Using default window of 10s \n")
  }

  val lines = scala.io.Source.fromFile("./beer.styles").mkString.split("\r\n")

  val storeBuilder = Stores.keyValueStoreBuilder(
    Stores.inMemoryKeyValueStore("cities_count"),
    Serdes.String,
    Serdes.String
  )

  //StreamsBuilder and Kstream are used to read data from or write data to a kafka stream
  val builder = new StreamsBuilder
  builder.addStateStore(storeBuilder)

  val events: KStream[String, cityInput] =
    builder
      .stream[String, cityInput](
        "events"
      )
      .filter((_, cityInput) => lines contains cityInput.style)

  val outputs =
    events.transform(() => { new cityTransform(timeWindow) }, "cities_count")

  outputs.to("updates")

  val topology = builder.build()

  val streams: KafkaStreams = new KafkaStreams(builder.build(), props)
  streams.start()

  sys.ShutdownHookThread {
    val timeout = 10;
    streams.close(Duration.ofSeconds(timeout))
  }
}
