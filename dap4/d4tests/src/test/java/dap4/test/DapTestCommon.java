/* Copyright 2012, UCAR/Unidata.
   See the LICENSE file for more information.
*/

package dap4.test;

import dap4.core.util.DapException;
import dap4.core.util.DapUtil;
import dap4.servlet.DapController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.web.WebAppConfiguration;
import ucar.httpservices.HTTPFactory;
import ucar.httpservices.HTTPMethod;
import ucar.httpservices.HTTPUtil;
import ucar.nc2.dataset.DatasetUrl;
import ucar.nc2.dataset.NetcdfDataset;
import ucar.unidata.test.util.TestDir;

import javax.servlet.ServletException;
import java.io.*;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.EnumSet;
import java.util.Set;

@ContextConfiguration
@WebAppConfiguration("file:src/test/data")
abstract public class DapTestCommon
{
    private final static Logger logger = LoggerFactory.getLogger(DapTestCommon.class);

    //////////////////////////////////////////////////
    // Constants

    static final boolean DEBUG = false;

    static protected final Charset UTF8 = Charset.forName("UTF-8");

    static final String DEFAULTTREEROOT = "dap4";
    // Look for these to verify we have found the thredds root
    static final String[] DEFAULTSUBDIRS = new String[]{"httpservices", "cdm", "tds", "opendap", "dap4"};

    static public final String FILESERVER = "file://localhost:8080";

    // NetcdfDataset enhancement to use: need only coord systems
    static Set<NetcdfDataset.Enhance> ENHANCEMENT = EnumSet.of(NetcdfDataset.Enhance.CoordSystems);

    static public final String CONSTRAINTTAG = "dap4.ce";

    static final String D4TESTDIRNAME = "d4tests";

    // Equivalent to the path to the webapp/d4ts for testing purposes
    static protected final String DFALTRESOURCEPATH = "/src/test/data/resources";
    //////////////////////////////////////////////////
    // Type decls

    static public class Mocker
    {
        public MockHttpServletRequest req = null;
        public MockHttpServletResponse resp = null;
        public MockServletContext context = null;
        public String url = null;
        public String servletname = null;
        public DapTestCommon parent = null;

        public DapController controller = null;

        public Mocker(String servletname, String url, DapTestCommon parent)
                throws Exception
        {
            this.parent = parent;
            this.url = url;
            this.servletname = servletname;
            String testdir = parent.getResourceRoot();
            // There appears to be bug in the spring core.io code
            // such that it assumes absolute paths start with '/'.
            // So, check for windows drive and prepend 'file:/' as a hack.
            if(DapUtil.hasDriveLetter(testdir))
                testdir = "/" + testdir;
            testdir = "file:" + testdir;
            this.context = new MockServletContext(testdir);
            URI u = HTTPUtil.parseToURI(url);
            this.req = new MockHttpServletRequest(this.context, "GET", u.getPath());
            this.resp = new MockHttpServletResponse();
            req.setMethod("GET");
            setup();
        }

        protected Mocker
        setController(DapController ct)
                throws ServletException
        {
            this.controller = ct;
            this.controller.TESTING = true;
            //this.controller.init();
            return this;
        }

        /**
         * The spring mocker is not very smart.
         * Given the url it should be possible
         * to initialize a lot of its fields.
         * Instead, it requires the user to so do.
         */
        protected void setup()
                throws Exception
        {
            this.req.setCharacterEncoding("UTF-8");
            URI u = HTTPUtil.parseToURI(this.url);
            this.req.setProtocol(u.getScheme());
            this.req.setQueryString(u.getQuery());
            this.req.setServerName(u.getHost());
            this.req.setServerPort(u.getPort());
            String path = u.getPath();
            if(path == null) path = "/";
            // Divide into contextpath + servletpath
            String[] pieces = path.split("[/]");
            int i;
            for(i=0;i<pieces.length;i++) {
                if(pieces[i].equals(this.servletname)) break;
            }
            if(i == pieces.length)
                throw new IllegalArgumentException("Bad mock uri path: "+path);
            String cp = "/" + DapUtil.join(pieces,"/",0,i);
            String sp = "/" + DapUtil.join(pieces,"/",i,pieces.length);
            this.req.setContextPath(cp);
            this.req.setServletPath(sp);
        }

        public byte[] execute()
                throws Exception
        {
            this.controller.handleRequest(this.req, this.resp);
            return this.resp.getContentAsByteArray();
        }
    }

    //////////////////////////////////////////////////
    // Static Variables

    static public org.slf4j.Logger log;

    static public boolean usingJenkins = (System.getenv("JENKINS_URL") != null);
    static public boolean usingTravis = (System.getenv("TRAVIS") != null);
    static public boolean usingIntellij = !(usingJenkins | usingTravis);

    //////////////////////////////////////////////////
    // Static methods

    // Walk around the directory structure to locate
    // the path to the thredds root (which may not
    // be names "thredds").
    // Same as code in UnitTestCommon, but for
    // some reason, Intellij will not let me import it.

    static String
    locateThreddsRoot()
    {
        // Walk up the user.dir path looking for a node that has
        // all the directories in SUBROOTS.

        String path = System.getProperty("user.dir");

        // clean up the path
        path = path.replace('\\', '/'); // only use forward slash
        assert (path != null);
        if(path.endsWith("/")) path = path.substring(0, path.length() - 1);

        File prefix = new File(path);
        for(; prefix != null; prefix = prefix.getParentFile()) {//walk up the tree
            int found = 0;
            String[] subdirs = prefix.list();
            for(String dirname : subdirs) {
                for(String want : DEFAULTSUBDIRS) {
                    if(dirname.equals(want)) {
                        found++;
                        break;
                    }
                }
            }
            if(found == DEFAULTSUBDIRS.length) try {// Assume this is it
                String root = prefix.getCanonicalPath();
                // clean up the root path
                root = root.replace('\\', '/'); // only use forward slash
                return root;
            } catch (IOException ioe) {
            }
        }
        return null;
    }

    static String
    locateDAP4Root(String threddsroot)
    {
        String root = threddsroot;
        if(root != null)
            root = root + "/" + DEFAULTTREEROOT;
        // See if it exists
        File f = new File(root);
        if(!f.exists() || !f.isDirectory())
            root = null;
        return root;
    }

    static protected String
    rebuildpath(String[] pieces, int last)
    {
        StringBuilder buf = new StringBuilder();
        for(int i = 0; i <= last; i++) {
            buf.append("/");
            buf.append(pieces[i]);
        }
        return buf.toString();
    }

    static public void
    clearDir(File dir, boolean clearsubdirs)
    {
        // wipe out the dir contents
        if(!dir.exists()) return;
        for(File f : dir.listFiles()) {
            if(f.isDirectory()) {
                if(clearsubdirs) {
                    clearDir(f, true); // clear subdirs
                    f.delete();
                }
            } else
                f.delete();
        }
    }

    //////////////////////////////////////////////////
    // Instance variables

    // System properties
    protected boolean prop_ascii = true;
    protected boolean prop_diff = true;
    protected boolean prop_baseline = false;
    protected boolean prop_visual = false;
    protected boolean prop_debug = DEBUG;
    protected boolean prop_generate = true;
    protected String prop_controls = null;

    // Define a tree pattern to recognize the root.
    protected String threddsroot = null;
    protected String dap4testroot = null;
    protected String d4tsServer = null;

    protected String title = "Testing";

    public DapTestCommon()
    {
        this("DapTest");
    }

    public DapTestCommon(String name)
    {
        this.title = name;
        setSystemProperties();
        initPaths();
    }

    protected void
    initPaths()
    {
        // Compute the root path
        this.threddsroot = locateThreddsRoot();
        if(this.threddsroot == null)
            System.err.println("Cannot locate /thredds parent dir");
        String dap4root = locateDAP4Root(this.threddsroot);
        if(dap4root == null)
            System.err.println("Cannot locate /dap4 parent dir");
        this.dap4testroot = dap4root + "/" + D4TESTDIRNAME;
        // Compute the set of SOURCES
        this.d4tsServer = TestDir.dap4TestServer;
        if(DEBUG)
            System.err.println("DapTestCommon: d4tsServer=" + d4tsServer);
    }

    /**
     * Try to get the system properties
     */
    protected void setSystemProperties()
    {
        String testargs = System.getProperty("testargs");
        if(testargs != null && testargs.length() > 0) {
            String[] pairs = testargs.split("[  ]*[,][  ]*");
            for(String pair : pairs) {
                String[] tuple = pair.split("[  ]*[=][  ]*");
                String value = (tuple.length == 1 ? "" : tuple[1]);
                if(tuple[0].length() > 0)
                    System.setProperty(tuple[0], value);
            }
        }
        if(System.getProperty("nodiff") != null)
            prop_diff = false;
        if(System.getProperty("baseline") != null)
            prop_baseline = true;
        if(System.getProperty("nogenerate") != null)
            prop_generate = false;
        if(System.getProperty("debug") != null)
            prop_debug = true;
        if(System.getProperty("visual") != null)
            prop_visual = true;
        if(System.getProperty("ascii") != null)
            prop_ascii = true;
        if(System.getProperty("utf8") != null)
            prop_ascii = false;
        if(prop_baseline && prop_diff)
            prop_diff = false;
        prop_controls = System.getProperty("controls", "");
    }

    //////////////////////////////////////////////////
    // Overrideable methods

    protected String getD4TestsRoot()
    {
         return this.dap4testroot;
    }

    protected String getResourceRoot()
    {
        return getD4TestsRoot() + DFALTRESOURCEPATH;
    }

    //////////////////////////////////////////////////
    // Accessor

    public void setTitle(String title)
    {
        this.title = title;
    }

    public String getTitle()
    {
        return this.title;
    }

    //////////////////////////////////////////////////
    // Instance Utilities

    public void
    visual(String header, String captured)
    {
        if(!captured.endsWith("\n"))
            captured = captured + "\n";
        // Dump the output for visual comparison
        System.out.println("Testing " + title + ": " + header + ":");
        System.out.println("---------------");
        System.out.print(captured);
        System.out.println("---------------");
    }

    public boolean

    compare(String baselinecontent, String testresult)
            throws Exception
    {
        StringReader baserdr = new StringReader(baselinecontent);
        StringReader resultrdr = new StringReader(testresult);
        // Diff the two files
        Diff diff = new Diff("Testing " + getTitle());
        boolean pass = !diff.doDiff(baserdr, resultrdr);
        baserdr.close();
        resultrdr.close();
        return pass;
    }

    protected void
    findServer(String path)
            throws DapException
    {
        if(d4tsServer.startsWith("file:")) {
            d4tsServer = FILESERVER + "/" + path;
        } else {
            String svc = "http://" + d4tsServer + "/d4ts";
            if(!checkServer(svc))
                logger.warn("D4TS Server not reachable: " + svc);
            // Since we will be accessing it thru NetcdfDataset, we need to change the schema.
            d4tsServer = "dap4://" + d4tsServer + "/d4ts";
        }
    }

    protected boolean
    checkServer(String candidate)
    {
        if(candidate == null) return false;
/* requires httpclient4
        int savecount = HTTPSession.getRetryCount();
        HTTPSession.setRetryCount(1);
*/
        // See if the sourceurl is available by trying to get the DSR
        System.err.print("Checking for sourceurl: " + candidate);
        try {
            try (HTTPMethod method = HTTPFactory.Get(candidate)) {
                method.execute();
                String s = method.getResponseAsString();
                System.err.println(" ; found");
                return true;
            }
        } catch (IOException ie) {
            System.err.println(" ; fail");
            return false;
        } finally {
// requires httpclient4            HTTPSession.setRetryCount(savecount);
        }
    }

    //////////////////////////////////////////////////
    // Static utilities

    // Copy result into the a specified dir
    static public void
    writefile(String path, String content)
            throws IOException
    {
        File f = new File(path);
        if(f.exists()) f.delete();
        FileWriter out = new FileWriter(f);
        out.write(content);
        out.close();
    }

    // Copy result into the a specified dir
    static public void
    writefile(String path, byte[] content)
            throws IOException
    {
        File f = new File(path);
        if(f.exists()) f.delete();
        FileOutputStream out = new FileOutputStream(f);
        out.write(content);
        out.close();
    }

    static public String
    readfile(String filename)
            throws IOException
    {
        StringBuilder buf = new StringBuilder();
        File xx = new File(filename);
        if(!xx.canRead()) {
            int x = 0;
        }
        FileReader file = new FileReader(filename);
        BufferedReader rdr = new BufferedReader(file);
        String line;
        while((line = rdr.readLine()) != null) {
            if(line.startsWith("#")) continue;
            buf.append(line + "\n");
        }
        return buf.toString();
    }

    static public byte[]
    readbinaryfile(String filename)
            throws IOException
    {
        FileInputStream file = new FileInputStream(filename);
        return DapUtil.readbinaryfile(file);
    }

    // Properly access a dataset
    static public NetcdfDataset openDataset(String url)
            throws IOException
    {
        DatasetUrl durl = DatasetUrl.findDatasetUrl(url);

        return NetcdfDataset.acquireDataset(null, durl, ENHANCEMENT, -1, null, null);
    }

    // Fix up a filename reference in a string
    static public String shortenFileName(String text, String filename)
    {
        // In order to achieve diff consistentcy, we need to
        // modify the output to change "netcdf .../file.nc {...}"
        // to "netcdf file.nc {...}"
        String fixed = filename.replace('\\', '/');
        String shortname = filename;
        if(fixed.lastIndexOf('/') >= 0)
            shortname = filename.substring(fixed.lastIndexOf('/') + 1, filename.length());
        text = text.replaceAll(filename, shortname);
        return text;
    }

    static public void
    tag(String t)
    {
        System.err.println(t);
        System.err.flush();
    }

    static public String canonjoin(String prefix, String suffix)
    {
        if(prefix == null) prefix = "";
        if(suffix == null) suffix = "";
        StringBuilder result = new StringBuilder(prefix);
        if(!prefix.endsWith("/"))
            result.append("/");
        result.append(suffix.startsWith("/") ? suffix.substring(1) : suffix);
        return result.toString();
    }

}

