package org.batfish.minesweeper.question;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.auto.service.AutoService;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.batfish.common.Answerer;
import org.batfish.common.plugin.IBatfish;
import org.batfish.common.plugin.Plugin;
import org.batfish.datamodel.answers.AnswerElement;
import org.batfish.datamodel.answers.StringAnswerElement;
import org.batfish.datamodel.questions.Question;
import org.batfish.minesweeper.nv.CompilerOptions;
import org.batfish.minesweeper.nv.CompilerOptions.NVFlags;
import org.batfish.minesweeper.nv.NVCompiler;
import org.batfish.minesweeper.utils.Tuple;
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
      CompilerOptions flags = new CompilerOptions();
      if (((CompileQuestion) _question).getNextHop()) {
        flags.setFlag(NVFlags.nexthop);
      }
      if (((CompileQuestion) _question).getOrigin()) {
        flags.setFlag(NVFlags.origin);
      }
      if (((CompileQuestion) _question).getDataPlane()) {
        flags.setFlag(NVFlags.dataplane);
      }
      NVCompiler c = new NVCompiler(_batfish, flags);
      Tuple<String, String> f = c.compile(q.getSinglePrefix());
      writeFile(q.getFile() + "_control.nv", f.getFirst());
      if (flags.doDataplane()) {
        writeFile(q.getFile() + "_data.nv", f.getSecond());
      }
      return new StringAnswerElement();
    }

    private void writeFile(String name, String contents) {
      try {
        File file = new File(name);
        FileWriter fileWriter = new FileWriter(file);
        fileWriter.write(contents);
        fileWriter.flush();
        fileWriter.close();
      } catch (IOException e) {
        System.out.println("An error occurred: could not write file.");
        e.printStackTrace();
      }
    }
  }

  public static class CompileQuestion extends HeaderQuestion {

    private static final String PROP_NEXTHOP = "doNextHop";
    private static final String PROP_ORIGIN = "doOrigin";

    private static final String PROP_FILE = "file";

    private boolean _nextHop = false;

    private boolean _origin = false;

    private String _file = "";

    @JsonProperty(PROP_NEXTHOP)
    public boolean getNextHop() {
      return _nextHop;
    }

    @JsonProperty(PROP_ORIGIN)
    public boolean getOrigin() {
      return _origin;
    }

    @JsonProperty(PROP_NEXTHOP)
    public void setNextHop(boolean x) {
      this._nextHop = x;
    }

    @JsonProperty(PROP_ORIGIN)
    public void setOrigin(boolean x) {
      this._origin = x;
    }

    @JsonProperty(PROP_FILE)
    public String getFile() {
      return _file;
    }

    @JsonProperty(PROP_FILE)
    public void setFile(String x) {
      this._file = x;
    }

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
