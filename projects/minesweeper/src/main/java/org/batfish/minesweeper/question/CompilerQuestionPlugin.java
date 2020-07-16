package org.batfish.minesweeper.question;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.auto.service.AutoService;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import org.batfish.common.Answerer;
import org.batfish.common.plugin.IBatfish;
import org.batfish.common.plugin.Plugin;
import org.batfish.datamodel.answers.AnswerElement;
import org.batfish.datamodel.answers.StringAnswerElement;
import org.batfish.datamodel.questions.Question;
import org.batfish.minesweeper.nv.CompilerOptions;
import org.batfish.minesweeper.nv.CompilerOptions.NVFlags;
import org.batfish.minesweeper.nv.CompilerResult;
import org.batfish.minesweeper.nv.NVCompiler;
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
      if (((CompileQuestion) _question).getData()) {
        flags.setFlag(NVFlags.dataplane);
      }
      if (((CompileQuestion) _question).getNodeFaults()) {
        flags.setFlag(NVFlags.nodeFaults);
      }

      NVCompiler c = new NVCompiler(_batfish, q.getFile() + "_control.nv", flags);
      CompilerResult f = c.compile(q.getSinglePrefix());
      String cpComment = "(* models bgp, ospf, static routes" +
                        (flags.doNextHop() ? ", nexthop" : "") +
                      (flags.doOrigin() ? ", route-origin" : "") + " *)\n\n";
      writeFile(q.getFile() + "_control.nv", f.getControlPlane(), cpComment);
      if (flags.doDataplane()) {
        writeFile(q.getFile() + "_data.nv", f.getDataPlane(), "");
      }
      if (flags.doNodeFaults()) {
        writeFile(q.getFile() + "_nodeFaults.nv", f.getAllNodeFaults(), "");
      }

      return new StringAnswerElement();
    }

    private void writeFile(String name, String contents, String comments) {
      try {
        File file = new File(name);
        FileWriter fileWriter = new FileWriter(file);
        fileWriter.write(comments);
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
    private static final String PROP_DATA = "doData";
    private static final String PROP_DO_NODE_FAULTS = "doNodeFaults";

    private static final String PROP_FILE = "file";

    private boolean _nextHop = false;

    private boolean _origin = false;

    private boolean _doNodeFaults = false;

    private boolean _data = false;

    private String _file = "";

    @JsonProperty(PROP_NEXTHOP)
    public boolean getNextHop() {
      return _nextHop;
    }

    @JsonProperty(PROP_ORIGIN)
    public boolean getOrigin() {
      return _origin;
    }

    @JsonProperty(PROP_DO_NODE_FAULTS)
    public boolean getNodeFaults() {
      return _doNodeFaults;
    }

    @JsonProperty(PROP_DATA)
    public boolean getData() { return _data; }

    @JsonProperty(PROP_FILE)
    public String getFile() {
      return _file;
    }

    @JsonProperty(PROP_NEXTHOP)
    public void setNextHop(boolean x) {
      this._nextHop = x;
    }

    @JsonProperty(PROP_ORIGIN)
    public void setOrigin(boolean x) {
      this._origin = x;
    }

    @JsonProperty(PROP_DATA)
    public void setData(boolean x) { this._data = x; }

    @JsonProperty(PROP_DO_NODE_FAULTS)
    public void setDoNodeFaults(boolean x) { this._doNodeFaults = x; }

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
