package domain.storedFilter

import akka.actor.ActorRef
import akka.persistence.{SnapshotOffer, PersistentActor}
import common.{ImplicitLogging, ImplicitActorLogging}
import protocol.storedFilter.{StoredFilter, NameOfAggregate}
import domain._

object StoredFilterAggregateRoot {

  //commands
  case class CreateNewStoredFilter(title: String, tags: Set[String]) extends Command
  object NewStoredFilter {
    def unapply(x: CreateNewStoredFilter)= Some(StoredFilter(x.title))
  }


  //events
  case class ItemCreated(entity: StoredFilter) extends Event

  case class StoredFilters(items: Map[String, StoredFilter] = Map.empty) extends State with ImplicitLogging {

    lazy val newItemId: String = {
      def generateNewItemId: String = {
        val id = scala.math.abs(scala.util.Random.nextInt()).toString
        if (items.keys.exists(_ == id)) generateNewItemId else id
      }
      generateNewItemId
    }

    def newItem(entity: StoredFilter) = entity.copy(id = newItemId)

    def update(event: Event): StoredFilters = {
      event match {
        case ItemCreated(entity) =>
          s"Event $event of StoredFilterAggregateRoot has been updated".logInfo()
          copy(items = items + (entity.id -> entity))
        case unknown =>
          s"Unknown event '$unknown' were found when updating ${this.getClass.getName} state.".logWarn()
          this
      }
    }
  }

}

class StoredFilterAggregateRoot extends PersistentActor with ImplicitActorLogging  {

  import StoredFilterAggregateRoot._

  val persistenceId: String = NameOfAggregate.root.name

  var state: StoredFilters = StoredFilters()

  val receiveCommand: Receive = {

    case NewStoredFilter(entity) =>

      def doPersistence(entity: StoredFilter) = {
        def afterPersisted(`sender`: ActorRef, evt: ItemCreated) = {
          state = state.update(evt)
          `sender` ! evt
        }
        persist(ItemCreated(entity))(afterPersisted(sender(), _))
      }
      doPersistence(state.newItem(entity))
  }

  val receiveRecover: Receive = {
    case evt: Event =>
      state = state.update(evt)
    case SnapshotOffer(_, snapshot: State) =>
  }
}