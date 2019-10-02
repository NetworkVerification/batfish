package org.batfish.minesweeper.question;

import com.google.auto.service.AutoService;
import org.batfish.common.Answerer;
import org.batfish.common.plugin.IBatfish;
import org.batfish.common.plugin.Plugin;
import org.batfish.datamodel.answers.AnswerElement;
import org.batfish.datamodel.answers.StringAnswerElement;
import org.batfish.datamodel.questions.Question;
import org.batfish.minesweeper.nv.NVCompiler;
import org.batfish.minesweeper.question.HeaderQuestion;
import org.batfish.question.QuestionPlugin;

@AutoService(Plugin.class)
public class CompilerQuestionPlugin extends QuestionPlugin {

  public static class CompileAnswerer extends Answerer {

    public CompileAnswerer(Question question, IBatfish batfish) {
      super(question, batfish);
    }

    @Override
    public AnswerElement answer() {
      CompileQuestion q = (CompileQuestion) _question;
      NVCompiler c = new NVCompiler(_batfish);
      return new StringAnswerElement(c.compile(q.getSinglePrefix()));
    }
  }

  public static class CompileQuestion extends HeaderQuestion {

    @Override
    public boolean getDataPlane() {
      return false;
    }

    @Override
    public String getName() {
      return "compile";
    }
  }

  @Override
  protected Answerer createAnswerer(Question question, IBatfish batfish) {
    return new CompileAnswerer(question, batfish);
  }

  @Override
  protected Question createQuestion() {
    return new CompileQuestion();
  }
}
