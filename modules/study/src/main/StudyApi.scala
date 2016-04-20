package lila.study

import akka.actor.ActorRef

import chess.format.{ Forsyth, FEN }
import lila.hub.actorApi.map.Tell
import lila.hub.Sequencer
import lila.user.User

final class StudyApi(
    repo: StudyRepo,
    jsonView: JsonView,
    sequencers: ActorRef,
    socketHub: akka.actor.ActorRef) {

  def byId = repo byId _

  def create(user: User): Fu[Study] = {
    val study = Study.make(
      user = user.light,
      setup = Chapter.Setup(
        gameId = none,
        variant = chess.variant.Standard,
        orientation = chess.White))
    repo insert study inject study
  }

  def locationByRef(ref: Location.Ref): Fu[Option[Location]] =
    byId(ref.studyId) map (_ flatMap (_ location ref.chapterId))

  def locationById(id: Location.Ref.ID): Fu[Option[Location]] =
    (Location.Ref parseId id) ?? locationByRef

  def setMemberPosition(userId: User.ID, ref: Location.Ref, path: Path) = {
    repo.setMemberPosition(userId, ref, path) >>-
      sendTo(ref.studyId, Socket.MemberPosition(userId, Position.Ref(ref.chapterId, path)))
  }

  def addNode(ref: Location.Ref, path: Path, node: Node) = sequenceLocation(ref) { location =>
    (location.study canWrite node.by) ?? {
      val newChapter = location.chapter.updateRoot { root =>
        root.withChildren(_.addNodeAt(node, path))
      }
      repo.setChapter(location withChapter newChapter) >>
        repo.setMemberPosition(node.by, ref, path + node) >>-
        sendTo(ref.studyId, Socket.AddNode(Position.Ref(ref.chapterId, path), node))
    }
  }

  def deleteNodeAt(userId: User.ID, ref: Location.Ref, path: Path) = sequenceLocation(ref) { location =>
    (location.study canWrite userId) ?? {
      val newChapter = location.chapter.updateRoot { root =>
        root.withChildren(_.deleteNodeAt(path))
      }
      repo.setChapter(location withChapter newChapter) >>-
        sendTo(ref.studyId, Socket.DelNode(Position.Ref(ref.chapterId, path)))
    }
  }

  def promoteNodeAt(userId: User.ID, ref: Location.Ref, path: Path) = sequenceLocation(ref) { location =>
    (location.study canWrite userId) ?? {
      val newChapter = location.chapter.updateRoot { root =>
        root.withChildren(_.promoteNodeAt(path))
      }
      repo.setChapter(location withChapter newChapter)
    }
  }

  private def sequenceRef(refId: Location.Ref.ID)(f: Location.Ref => Funit): Funit =
    Location.Ref.parseId(refId) ?? { ref =>
      sequence(ref.studyId) {
        f(ref)
      }
    }

  private def sequenceLocation(ref: Location.Ref)(f: Location => Funit): Funit =
    locationByRef(ref) flatMap {
      _ ?? { location =>
        sequence(ref.studyId) {
          f(location)
        }
      }
    }

  private def sequence(studyId: String)(f: => Funit): Funit = {
    val promise = scala.concurrent.Promise[Unit]
    val work = Sequencer.work(f, promise.some)
    sequencers ! Tell(studyId, work)
    promise.future
  }

  private def sendTo(studyId: String, msg: Any) {
    socketHub ! Tell(studyId, msg)
  }
}