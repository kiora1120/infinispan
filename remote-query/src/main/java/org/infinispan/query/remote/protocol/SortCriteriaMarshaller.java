package org.infinispan.query.remote.protocol;

import org.infinispan.protostream.MessageMarshaller;

import java.io.IOException;

/**
 * @author anistor@redhat.com
 * @since 6.0
 */
public class SortCriteriaMarshaller implements MessageMarshaller<QueryRequest.SortCriteria> {

   @Override
   public String getFullName() {
      return "org.infinispan.client.hotrod.impl.query.QueryRequest.SortCriteria";
   }

   @Override
   public QueryRequest.SortCriteria readFrom(MessageMarshaller.ProtoStreamReader reader) throws IOException {
      QueryRequest.SortCriteria sortCriteria = new QueryRequest.SortCriteria();
      sortCriteria.setAttributePath(reader.readString("attributePath"));
      sortCriteria.setAscending(reader.readBoolean("isAscending"));
      return sortCriteria;
   }

   @Override
   public void writeTo(MessageMarshaller.ProtoStreamWriter writer, QueryRequest.SortCriteria sortCriteria) throws IOException {
      writer.writeString("attributePath", sortCriteria.getAttributePath());
      writer.writeBoolean("isAscending", sortCriteria.isAscending());
   }
}
