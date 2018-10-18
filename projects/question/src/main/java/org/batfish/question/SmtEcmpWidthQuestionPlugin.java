package org.batfish.question;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.auto.service.AutoService;
import org.batfish.common.Answerer;
import org.batfish.common.plugin.IBatfish;
import org.batfish.common.plugin.Plugin;
import org.batfish.datamodel.answers.AnswerElement;
import org.batfish.datamodel.questions.Question;
import org.batfish.datamodel.questions.smt.HeaderLocationQuestion;

@AutoService(Plugin.class)
public class SmtEcmpWidthQuestionPlugin extends QuestionPlugin {
  public static class EcmpWidthAnswerer extends Answerer {

    public EcmpWidthAnswerer(Question question, IBatfish batfish) {
      super(question, batfish);
    }

    @Override public AnswerElement answer() {
      EcmpWidthQuestion q = (EcmpWidthQuestion) _question;
      return _batfish.smtEcmpWidth(q, q.getWidth());
    }
  }

  public static class EcmpWidthQuestion extends HeaderLocationQuestion {

    private static final String ECMP_WIDTH_VAR = "ecmp-width";

    private int _width;

    public EcmpWidthQuestion() {
      _width = 0;
    }

    @JsonProperty(ECMP_WIDTH_VAR)
    public int getWidth() {
      return _width;
    }

    @JsonProperty(ECMP_WIDTH_VAR)
    public void setWidth(int i) {
      this._width = i;
    }

    @Override
    public boolean getDataPlane() {
      return false;
    }

    @Override
    public String getName() {
      return "smt-ecmp-width";
    }
  }

  @Override
  protected Answerer createAnswerer(Question question, IBatfish batfish) {
    return new EcmpWidthAnswerer(question, batfish);
  }

  @Override
  protected Question createQuestion() { return new EcmpWidthQuestion(); }
}
