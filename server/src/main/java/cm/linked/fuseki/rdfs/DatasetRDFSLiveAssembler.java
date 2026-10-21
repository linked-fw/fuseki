package cm.linked.fuseki.rdfs;

import org.apache.jena.assembler.Assembler;
import org.apache.jena.assembler.exceptions.AssemblerException;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.assembler.AssemblerUtils;
import org.apache.jena.sparql.core.assembler.DatasetAssembler;
import org.apache.jena.sparql.util.graph.GraphUtils;
import org.apache.jena.tdb2.store.DatasetGraphSwitchable;
import org.apache.jena.vocabulary.RDFS;

import cm.linked.fuseki.LF;

/**
 * <pre>
 * &lt;#ds&gt;  a lf:DatasetRDFSLive ; ja:dataset &lt;#tdb&gt; .
 * &lt;#tdb&gt; a tdb2:DatasetTDB2 ; tdb2:location "/fuseki-base/databases/name" .
 * </pre>
 */
public class DatasetRDFSLiveAssembler extends DatasetAssembler {

    public static Resource getType() {
        return LF.DatasetRDFSLive;
    }

    @Override
    protected DatasetGraph createDataset(Assembler a, Resource root) {
        Resource base = GraphUtils.getResourceValue(root, org.apache.jena.sparql.core.assembler.DatasetAssemblerVocab.pDataset);
        if ( base == null )
            throw new AssemblerException(root, "Required base dataset missing (ja:dataset): " + root);
        DatasetGraph stored = createBaseDataset(root, org.apache.jena.sparql.core.assembler.DatasetAssemblerVocab.pDataset);
        DatasetGraphRDFSLive dsg = new DatasetGraphRDFSLive(stored, label(root, stored));
        AssemblerUtils.mergeContext(root, dsg.getContext());
        return dsg;
    }

    private static String label(Resource root, DatasetGraph stored) {
        if ( stored instanceof DatasetGraphSwitchable sw && sw.getLocation() != null && sw.getLocation().getDirectoryPath() != null )
            return sw.getLocation().getDirectoryPath();
        if ( root.isURIResource() )
            return root.getURI();
        if ( root.hasProperty(RDFS.label) )
            return root.getProperty(RDFS.label).getString();
        return root.toString();
    }
}
