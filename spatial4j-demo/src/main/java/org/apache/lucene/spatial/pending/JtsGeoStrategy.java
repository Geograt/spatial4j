package org.apache.lucene.spatial.pending;

import org.apache.lucene.document.DocValuesField;
import org.apache.lucene.index.DocValues.Type;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.queries.function.ValueSource;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.Filter;
import org.apache.lucene.search.Query;
import org.apache.lucene.spatial.SimpleSpatialFieldInfo;
import org.apache.lucene.spatial.SpatialStrategy;
import org.apache.lucene.util.BytesRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spatial4j.core.context.jts.JtsSpatialContext;
import com.spatial4j.core.exception.InvalidShapeException;
import com.spatial4j.core.query.SpatialArgs;
import com.spatial4j.core.shape.Shape;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.io.WKBWriter;
import com.vividsolutions.jts.simplify.TopologyPreservingSimplifier;

/**
 * Put raw WKB in DocValues
 */
public class JtsGeoStrategy extends SpatialStrategy<SimpleSpatialFieldInfo> {

  private static final Logger logger = LoggerFactory.getLogger(JtsGeoStrategy.class);

  private final JtsSpatialContext shapeIO;
  private int max_wkb_length = 32000;

  public JtsGeoStrategy(JtsSpatialContext shapeIO) {
    super(shapeIO);
    this.shapeIO = shapeIO;
  }

  @Override
  public IndexableField createField(SimpleSpatialFieldInfo indexInfo, Shape shape, boolean index, boolean store) {
    Geometry geo = shapeIO.getGeometryFrom(shape);

    WKBWriter writer = new WKBWriter();
    BytesRef wkb = new BytesRef(writer.write(geo));

    if (max_wkb_length > 0 && wkb.length > max_wkb_length) {
      long last = wkb.length;
      Envelope env = geo.getEnvelopeInternal();
      double mins = Math.min(env.getWidth(), env.getHeight());
      double div = 1000;
      while (true) {
        double tolerance = mins / div;
        if (logger.isInfoEnabled()) {
          logger.info("Simplifying long geometry: WKB.length=" + wkb.length + " tolerance=" + tolerance);
        }
        Geometry simple = TopologyPreservingSimplifier.simplify(geo, tolerance);
        wkb = new BytesRef(writer.write(simple));
        if (wkb.length < max_wkb_length) {
          break;
        }
        if (wkb.length == last) {
          throw new InvalidShapeException("Can not simplify geometry smaller then max. " + last);
        }
        last = wkb.length;
        div *= .70;
      }
    }

    return new DocValuesField(indexInfo.getFieldName(), wkb, Type.BYTES_VAR_DEREF);
  }

  @Override
  public ValueSource makeValueSource(SpatialArgs args, SimpleSpatialFieldInfo fieldInfo) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Query makeQuery(SpatialArgs args, SimpleSpatialFieldInfo field) {
    Filter f = makeFilter(args, field);
    // TODO... could add in scoring here..
    return new ConstantScoreQuery( f );
  }

  @Override
  public Filter makeFilter(SpatialArgs args, SimpleSpatialFieldInfo field) {
    Geometry geo = shapeIO.getGeometryFrom(args.getShape());
    GeometryTest tester = GeometryTestFactory.get(args.getOperation(), geo);
    return new GeometryOperationFilter(field.getFieldName(), tester, shapeIO);
  }
}