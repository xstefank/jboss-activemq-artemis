/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.artemis.jms.client;

import javax.jms.DeliveryMode;
import javax.jms.Destination;
import javax.jms.IllegalStateException;
import javax.jms.InvalidDestinationException;
import javax.jms.JMSException;
import javax.jms.JMSRuntimeException;
import javax.jms.Message;
import javax.jms.MessageFormatException;
import javax.jms.MessageNotWriteableException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import org.apache.activemq.artemis.api.core.ActiveMQBuffer;
import org.apache.activemq.artemis.api.core.ActiveMQException;
import org.apache.activemq.artemis.api.core.ActiveMQPropertyConversionException;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.client.ClientMessage;
import org.apache.activemq.artemis.api.core.client.ClientSession;
import org.apache.activemq.artemis.api.jms.ActiveMQJMSConstants;
import org.apache.activemq.artemis.core.message.impl.MessageInternal;
import org.apache.activemq.artemis.reader.MessageUtil;
import org.apache.activemq.artemis.utils.UUID;

/**
 * ActiveMQ Artemis implementation of a JMS Message.
 * <br>
 * JMS Messages only live on the client side - the server only deals with MessageImpl
 * instances
 */
public class ActiveMQMessage implements javax.jms.Message {

   // Constants -----------------------------------------------------
   public static final byte TYPE = org.apache.activemq.artemis.api.core.Message.DEFAULT_TYPE;

   public static Map<String, Object> coreMaptoJMSMap(final Map<String, Object> coreMessage) {
      Map<String, Object> jmsMessage = new HashMap<String, Object>();

      String deliveryMode = (Boolean) coreMessage.get("durable") ? "PERSISTENT" : "NON_PERSISTENT";
      byte priority = (Byte) coreMessage.get("priority");
      long timestamp = (Long) coreMessage.get("timestamp");
      long expiration = (Long) coreMessage.get("expiration");

      jmsMessage.put("JMSPriority", priority);
      jmsMessage.put("JMSTimestamp", timestamp);
      jmsMessage.put("JMSExpiration", expiration);
      jmsMessage.put("JMSDeliveryMode", deliveryMode);

      for (Map.Entry<String, Object> entry : coreMessage.entrySet()) {
         if (entry.getKey().equals("type") || entry.getKey().equals("durable") ||
            entry.getKey().equals("expiration") ||
            entry.getKey().equals("timestamp") ||
            entry.getKey().equals("priority")) {
            // Ignore
         }
         else if (entry.getKey().equals("userID")) {
            jmsMessage.put("JMSMessageID", entry.getValue().toString());
         }
         else {
            Object value = entry.getValue();
            if (value instanceof SimpleString) {
               jmsMessage.put(entry.getKey(), value.toString());
            }
            else {
               jmsMessage.put(entry.getKey(), value);
            }
         }
      }

      return jmsMessage;
   }

   // Static --------------------------------------------------------

   private static final HashSet<String> reservedIdentifiers = new HashSet<String>();

   static {
      ActiveMQMessage.reservedIdentifiers.add("NULL");
      ActiveMQMessage.reservedIdentifiers.add("TRUE");
      ActiveMQMessage.reservedIdentifiers.add("FALSE");
      ActiveMQMessage.reservedIdentifiers.add("NOT");
      ActiveMQMessage.reservedIdentifiers.add("AND");
      ActiveMQMessage.reservedIdentifiers.add("OR");
      ActiveMQMessage.reservedIdentifiers.add("BETWEEN");
      ActiveMQMessage.reservedIdentifiers.add("LIKE");
      ActiveMQMessage.reservedIdentifiers.add("IN");
      ActiveMQMessage.reservedIdentifiers.add("IS");
      ActiveMQMessage.reservedIdentifiers.add("ESCAPE");
   }

   public static ActiveMQMessage createMessage(final ClientMessage message, final ClientSession session) {
      return createMessage(message, session, null);
   }

   public static ActiveMQMessage createMessage(final ClientMessage message, final ClientSession session, final ConnectionFactoryOptions options) {
      int type = message.getType();

      ActiveMQMessage msg;

      switch (type) {
         case ActiveMQMessage.TYPE: // 0
         {
            msg = new ActiveMQMessage(message, session);
            break;
         }
         case ActiveMQBytesMessage.TYPE: // 4
         {
            msg = new ActiveMQBytesMessage(message, session);
            break;
         }
         case ActiveMQMapMessage.TYPE: // 5
         {
            msg = new ActiveMQMapMessage(message, session);
            break;
         }
         case ActiveMQObjectMessage.TYPE: {
            msg = new ActiveMQObjectMessage(message, session, options);
            break;
         }
         case ActiveMQStreamMessage.TYPE: // 6
         {
            msg = new ActiveMQStreamMessage(message, session);
            break;
         }
         case ActiveMQTextMessage.TYPE: // 3
         {
            msg = new ActiveMQTextMessage(message, session);
            break;
         }
         default: {
            throw new JMSRuntimeException("Invalid message type " + type);
         }
      }

      return msg;
   }

   // Attributes ----------------------------------------------------

   // The underlying message
   protected ClientMessage message;

   private ClientSession session;

   // Read-only?
   protected boolean readOnly;

   // Properties read-only?
   protected boolean propertiesReadOnly;

   // Cache it
   private Destination dest;

   // Cache it
   private String msgID;

   // Cache it
   private Destination replyTo;

   // Cache it
   private String jmsCorrelationID;

   // Cache it
   private String jmsType;

   private boolean individualAck;

   private long jmsDeliveryTime;

   // Constructors --------------------------------------------------

   /*
    * Create a new message prior to sending
    */
   protected ActiveMQMessage(final byte type, final ClientSession session) {
      message = session.createMessage(type, true, 0, System.currentTimeMillis(), (byte) 4);
   }

   protected ActiveMQMessage(final ClientSession session) {
      this(ActiveMQMessage.TYPE, session);
   }

   /**
    * Constructor for when receiving a message from the server
    */
   public ActiveMQMessage(final ClientMessage message, final ClientSession session) {
      this.message = message;

      readOnly = true;

      propertiesReadOnly = true;

      this.session = session;
   }

   /*
    * A constructor that takes a foreign message
    */
   public ActiveMQMessage(final Message foreign, final ClientSession session) throws JMSException {
      this(foreign, ActiveMQMessage.TYPE, session);
   }

   public ActiveMQMessage() {
   }

   protected ActiveMQMessage(final Message foreign, final byte type, final ClientSession session) throws JMSException {
      this(type, session);

      setJMSTimestamp(foreign.getJMSTimestamp());

      String value = System.getProperty(ActiveMQJMSConstants.JMS_ACTIVEMQ_ENABLE_BYTE_ARRAY_JMS_CORRELATION_ID_PROPERTY_NAME);

      boolean supportBytesId = !"false".equals(value);

      if (supportBytesId) {
         try {
            byte[] corrIDBytes = foreign.getJMSCorrelationIDAsBytes();
            setJMSCorrelationIDAsBytes(corrIDBytes);
         }
         catch (JMSException e) {
            // specified as String
            String corrIDString = foreign.getJMSCorrelationID();
            if (corrIDString != null) {
               setJMSCorrelationID(corrIDString);
            }
         }
      }
      else {
         // Some providers, like WSMQ do automatic conversions between native byte[] correlation id
         // and String correlation id. This makes it impossible for ActiveMQ Artemis to guarantee to return the correct
         // type as set by the user
         // So we allow the behaviour to be overridden by a system property
         // https://jira.jboss.org/jira/browse/HORNETQ-356
         // https://jira.jboss.org/jira/browse/HORNETQ-332
         String corrIDString = foreign.getJMSCorrelationID();
         if (corrIDString != null) {
            setJMSCorrelationID(corrIDString);
         }
      }

      setJMSReplyTo(foreign.getJMSReplyTo());
      setJMSDestination(foreign.getJMSDestination());
      setJMSDeliveryMode(foreign.getJMSDeliveryMode());
      setJMSExpiration(foreign.getJMSExpiration());
      setJMSPriority(foreign.getJMSPriority());
      setJMSType(foreign.getJMSType());

      // We can't avoid a cast warning here since getPropertyNames() is on the JMS API
      for (Enumeration<String> props = foreign.getPropertyNames(); props.hasMoreElements(); ) {
         String name = props.nextElement();

         Object prop = foreign.getObjectProperty(name);

         setObjectProperty(name, prop);
      }
   }

   // javax.jmx.Message implementation ------------------------------

   public String getJMSMessageID() {
      if (msgID == null) {
         UUID uid = message.getUserID();

         msgID = uid == null ? null : "ID:" + uid.toString();
      }
      return msgID;
   }

   public void setJMSMessageID(final String jmsMessageID) throws JMSException {
      if (jmsMessageID != null && !jmsMessageID.startsWith("ID:")) {
         throw new JMSException("JMSMessageID must start with ID:");
      }

      message.setUserID(null);

      msgID = jmsMessageID;
   }

   public long getJMSTimestamp() throws JMSException {
      return message.getTimestamp();
   }

   public void setJMSTimestamp(final long timestamp) throws JMSException {
      message.setTimestamp(timestamp);
   }

   public byte[] getJMSCorrelationIDAsBytes() throws JMSException {
      return MessageUtil.getJMSCorrelationIDAsBytes(message);
   }

   public void setJMSCorrelationIDAsBytes(final byte[] correlationID) throws JMSException {
      try {
         MessageUtil.setJMSCorrelationIDAsBytes(message, correlationID);
      }
      catch (ActiveMQException e) {
         JMSException ex = new JMSException(e.getMessage());
         ex.initCause(e);
         throw ex;
      }
   }

   public void setJMSCorrelationID(final String correlationID) throws JMSException {
      MessageUtil.setJMSCorrelationID(message, correlationID);
      jmsCorrelationID = correlationID;
   }

   public String getJMSCorrelationID() throws JMSException {
      if (jmsCorrelationID == null) {
         jmsCorrelationID = MessageUtil.getJMSCorrelationID(message);
      }

      return jmsCorrelationID;
   }

   public Destination getJMSReplyTo() throws JMSException {
      if (replyTo == null) {

         SimpleString repl = MessageUtil.getJMSReplyTo(message);

         if (repl != null) {
            replyTo = ActiveMQDestination.fromAddress(repl.toString());
         }
      }
      return replyTo;
   }

   public void setJMSReplyTo(final Destination dest) throws JMSException {

      if (dest == null) {
         MessageUtil.setJMSReplyTo(message, null);
         replyTo = null;
      }
      else {
         if (dest instanceof ActiveMQDestination == false) {
            throw new InvalidDestinationException("Foreign destination " + dest);
         }

         ActiveMQDestination jbd = (ActiveMQDestination) dest;

         MessageUtil.setJMSReplyTo(message, jbd.getSimpleAddress());

         replyTo = jbd;
      }
   }

   public Destination getJMSDestination() throws JMSException {
      if (dest == null) {
         SimpleString sdest = message.getAddress();

         dest = sdest == null ? null : ActiveMQDestination.fromAddress(sdest.toString());
      }

      return dest;
   }

   public void setJMSDestination(final Destination destination) throws JMSException {
      dest = destination;
   }

   public int getJMSDeliveryMode() throws JMSException {
      return message.isDurable() ? DeliveryMode.PERSISTENT : DeliveryMode.NON_PERSISTENT;
   }

   public void setJMSDeliveryMode(final int deliveryMode) throws JMSException {
      if (deliveryMode == DeliveryMode.PERSISTENT) {
         message.setDurable(true);
      }
      else if (deliveryMode == DeliveryMode.NON_PERSISTENT) {
         message.setDurable(false);
      }
      else {
         throw ActiveMQJMSClientBundle.BUNDLE.illegalDeliveryMode(deliveryMode);
      }
   }

   public boolean getJMSRedelivered() throws JMSException {
      return message.getDeliveryCount() > 1;
   }

   public void setJMSRedelivered(final boolean redelivered) throws JMSException {
      if (!redelivered) {
         message.setDeliveryCount(1);
      }
      else {
         if (message.getDeliveryCount() > 1) {
            // do nothing
         }
         else {
            message.setDeliveryCount(2);
         }
      }
   }

   public void setJMSType(final String type) throws JMSException {
      if (type != null) {
         MessageUtil.setJMSType(message, type);

         jmsType = type;
      }
   }

   public String getJMSType() throws JMSException {
      if (jmsType == null) {
         jmsType = MessageUtil.getJMSType(message);
      }
      return jmsType;
   }

   public long getJMSExpiration() throws JMSException {
      return message.getExpiration();
   }

   public void setJMSExpiration(final long expiration) throws JMSException {
      message.setExpiration(expiration);
   }

   public int getJMSPriority() throws JMSException {
      return message.getPriority();
   }

   public void setJMSPriority(final int priority) throws JMSException {
      checkPriority(priority);

      message.setPriority((byte) priority);
   }

   public void clearProperties() throws JMSException {

      MessageUtil.clearProperties(message);

      propertiesReadOnly = false;
   }

   public void clearBody() throws JMSException {
      readOnly = false;
   }

   public boolean propertyExists(final String name) throws JMSException {
      return MessageUtil.propertyExists(message, name);
   }

   public boolean getBooleanProperty(final String name) throws JMSException {
      try {
         return message.getBooleanProperty(new SimpleString(name));
      }
      catch (ActiveMQPropertyConversionException e) {
         throw new MessageFormatException(e.getMessage());
      }
   }

   public byte getByteProperty(final String name) throws JMSException {
      try {
         return message.getByteProperty(new SimpleString(name));
      }
      catch (ActiveMQPropertyConversionException e) {
         throw new MessageFormatException(e.getMessage());
      }
   }

   public short getShortProperty(final String name) throws JMSException {
      try {
         return message.getShortProperty(new SimpleString(name));
      }
      catch (ActiveMQPropertyConversionException e) {
         throw new MessageFormatException(e.getMessage());
      }
   }

   public int getIntProperty(final String name) throws JMSException {
      if (MessageUtil.JMSXDELIVERYCOUNT.equals(name)) {
         return message.getDeliveryCount();
      }

      try {
         return message.getIntProperty(new SimpleString(name));
      }
      catch (ActiveMQPropertyConversionException e) {
         throw new MessageFormatException(e.getMessage());
      }
   }

   public long getLongProperty(final String name) throws JMSException {
      if (MessageUtil.JMSXDELIVERYCOUNT.equals(name)) {
         return message.getDeliveryCount();
      }

      try {
         return message.getLongProperty(new SimpleString(name));
      }
      catch (ActiveMQPropertyConversionException e) {
         throw new MessageFormatException(e.getMessage());
      }
   }

   public float getFloatProperty(final String name) throws JMSException {
      try {
         return message.getFloatProperty(new SimpleString(name));
      }
      catch (ActiveMQPropertyConversionException e) {
         throw new MessageFormatException(e.getMessage());
      }
   }

   public double getDoubleProperty(final String name) throws JMSException {
      try {
         return message.getDoubleProperty(new SimpleString(name));
      }
      catch (ActiveMQPropertyConversionException e) {
         throw new MessageFormatException(e.getMessage());
      }
   }

   public String getStringProperty(final String name) throws JMSException {
      if (MessageUtil.JMSXDELIVERYCOUNT.equals(name)) {
         return String.valueOf(message.getDeliveryCount());
      }

      try {
         if (MessageUtil.JMSXGROUPID.equals(name)) {
            return message.getStringProperty(org.apache.activemq.artemis.api.core.Message.HDR_GROUP_ID);
         }
         else {
            return message.getStringProperty(new SimpleString(name));
         }
      }
      catch (ActiveMQPropertyConversionException e) {
         throw new MessageFormatException(e.getMessage());
      }
   }

   public Object getObjectProperty(final String name) throws JMSException {
      if (MessageUtil.JMSXDELIVERYCOUNT.equals(name)) {
         return String.valueOf(message.getDeliveryCount());
      }

      Object val = message.getObjectProperty(name);
      if (val instanceof SimpleString) {
         val = ((SimpleString) val).toString();
      }
      return val;
   }

   @SuppressWarnings("rawtypes")
   @Override
   public Enumeration getPropertyNames() throws JMSException {
      return Collections.enumeration(MessageUtil.getPropertyNames(message));
   }

   public void setBooleanProperty(final String name, final boolean value) throws JMSException {
      checkProperty(name);

      message.putBooleanProperty(new SimpleString(name), value);
   }

   public void setByteProperty(final String name, final byte value) throws JMSException {
      checkProperty(name);
      message.putByteProperty(new SimpleString(name), value);
   }

   public void setShortProperty(final String name, final short value) throws JMSException {
      checkProperty(name);
      message.putShortProperty(new SimpleString(name), value);
   }

   public void setIntProperty(final String name, final int value) throws JMSException {
      checkProperty(name);
      message.putIntProperty(new SimpleString(name), value);
   }

   public void setLongProperty(final String name, final long value) throws JMSException {
      checkProperty(name);
      message.putLongProperty(new SimpleString(name), value);
   }

   public void setFloatProperty(final String name, final float value) throws JMSException {
      checkProperty(name);
      message.putFloatProperty(new SimpleString(name), value);
   }

   public void setDoubleProperty(final String name, final double value) throws JMSException {
      checkProperty(name);
      message.putDoubleProperty(new SimpleString(name), value);
   }

   public void setStringProperty(final String name, final String value) throws JMSException {
      checkProperty(name);

      if (MessageUtil.JMSXGROUPID.equals(name)) {
         message.putStringProperty(org.apache.activemq.artemis.api.core.Message.HDR_GROUP_ID, SimpleString.toSimpleString(value));
      }
      else {
         message.putStringProperty(new SimpleString(name), SimpleString.toSimpleString(value));
      }
   }

   public void setObjectProperty(final String name, final Object value) throws JMSException {
      if (ActiveMQJMSConstants.JMS_ACTIVEMQ_OUTPUT_STREAM.equals(name)) {
         setOutputStream((OutputStream) value);

         return;
      }
      else if (ActiveMQJMSConstants.JMS_ACTIVEMQ_SAVE_STREAM.equals(name)) {
         saveToOutputStream((OutputStream) value);

         return;
      }

      checkProperty(name);

      if (ActiveMQJMSConstants.JMS_ACTIVEMQ_INPUT_STREAM.equals(name)) {
         setInputStream((InputStream) value);

         return;
      }

      try {
         message.putObjectProperty(new SimpleString(name), value);
      }
      catch (ActiveMQPropertyConversionException e) {
         throw new MessageFormatException(e.getMessage());
      }
   }

   public void acknowledge() throws JMSException {
      if (session != null) {
         try {
            if (individualAck) {
               message.individualAcknowledge();
            }

            session.commit();
         }
         catch (ActiveMQException e) {
            throw JMSExceptionHelper.convertFromActiveMQException(e);
         }
      }
   }

   @Override
   public long getJMSDeliveryTime() throws JMSException {
      Long value;
      try {
         value = message.getLongProperty(org.apache.activemq.artemis.api.core.Message.HDR_SCHEDULED_DELIVERY_TIME);
      }
      catch (Exception e) {
         return 0;
      }

      if (value == null) {
         return 0;
      }
      else {
         return value.longValue();
      }
   }

   @Override
   public void setJMSDeliveryTime(long deliveryTime) throws JMSException {
      message.putLongProperty(org.apache.activemq.artemis.api.core.Message.HDR_SCHEDULED_DELIVERY_TIME, deliveryTime);
   }

   @Override
   public <T> T getBody(Class<T> c) throws JMSException {
      if (isBodyAssignableTo(c)) {
         return getBodyInternal(c);
      }
      // XXX HORNETQ-1209 Do we need translations here?
      throw new MessageFormatException("Body not assignable to " + c);
   }

   @SuppressWarnings("unchecked")
   protected <T> T getBodyInternal(Class<T> c) throws MessageFormatException {
      InputStream is = ((MessageInternal) message).getBodyInputStream();
      try {
         ObjectInputStream ois = new ObjectInputStream(is);
         return (T) ois.readObject();
      }
      catch (Exception e) {
         throw new MessageFormatException(e.getMessage());
      }
   }

   @Override
   public boolean isBodyAssignableTo(@SuppressWarnings("rawtypes") Class c) {
      /**
       * From the specs:
       * <p>
       * If the message is a {@code Message} (but not one of its subtypes) then this method will
       * return true irrespective of the value of this parameter.
       */
      return true;
   }

   /**
    * Helper method for {@link #isBodyAssignableTo(Class)}.
    *
    * @return true if the message has no body.
    */
   protected boolean hasNoBody() {
      return message.getBodySize() == 0;
   }

   // Public --------------------------------------------------------

   public void setIndividualAcknowledge() {
      this.individualAck = true;
   }

   public void resetMessageID(final String newMsgID) {
      this.msgID = newMsgID;
   }

   public ClientMessage getCoreMessage() {
      return message;
   }

   public void doBeforeSend() throws Exception {
      message.getBodyBuffer().resetReaderIndex();
   }

   public void checkBuffer() {
      message.getBodyBuffer();
   }

   public void doBeforeReceive() throws ActiveMQException {
      message.checkCompletion();

      ActiveMQBuffer body = message.getBodyBuffer();

      if (body != null) {
         body.resetReaderIndex();
      }
   }

   public byte getType() {
      return ActiveMQMessage.TYPE;
   }

   public void setInputStream(final InputStream input) throws JMSException {
      checkStream();
      if (readOnly) {
         throw ActiveMQJMSClientBundle.BUNDLE.messageNotWritable();
      }

      message.setBodyInputStream(input);
   }

   public void setOutputStream(final OutputStream output) throws JMSException {
      checkStream();
      if (!readOnly) {
         throw new IllegalStateException("OutputStream property is only valid on received messages");
      }

      try {
         message.setOutputStream(output);
      }
      catch (ActiveMQException e) {
         throw JMSExceptionHelper.convertFromActiveMQException(e);
      }
   }

   public void saveToOutputStream(final OutputStream output) throws JMSException {
      checkStream();
      if (!readOnly) {
         throw new IllegalStateException("OutputStream property is only valid on received messages");
      }

      try {
         message.saveToOutputStream(output);
      }
      catch (ActiveMQException e) {
         throw JMSExceptionHelper.convertFromActiveMQException(e);
      }
   }

   public boolean waitCompletionOnStream(final long timeWait) throws JMSException {
      checkStream();
      try {
         return message.waitOutputStreamCompletion(timeWait);
      }
      catch (ActiveMQException e) {
         throw JMSExceptionHelper.convertFromActiveMQException(e);
      }
   }

   @Override
   public String toString() {
      StringBuffer sb = new StringBuffer("ActiveMQMessage[");
      sb.append(getJMSMessageID());
      sb.append("]:");
      sb.append(message.isDurable() ? "PERSISTENT" : "NON-PERSISTENT");
      sb.append("/" + message.toString());
      return sb.toString();
   }

   // Package protected ---------------------------------------------

   // Protected -----------------------------------------------------

   protected void checkWrite() throws JMSException {
      if (readOnly) {
         throw ActiveMQJMSClientBundle.BUNDLE.messageNotWritable();
      }
   }

   protected void checkRead() throws JMSException {
      if (!readOnly) {
         throw ActiveMQJMSClientBundle.BUNDLE.messageNotReadable();
      }
   }

   // Private ------------------------------------------------------------

   private void checkStream() throws JMSException {
      if (!(message.getType() == ActiveMQBytesMessage.TYPE || message.getType() == ActiveMQStreamMessage.TYPE)) {
         throw ActiveMQJMSClientBundle.BUNDLE.onlyValidForByteOrStreamMessages();
      }
   }

   private void checkProperty(final String name) throws JMSException {
      if (propertiesReadOnly) {
         if (name.equals(ActiveMQJMSConstants.JMS_ACTIVEMQ_INPUT_STREAM)) {
            throw new MessageNotWriteableException("You cannot set the Input Stream on received messages. Did you mean " + ActiveMQJMSConstants.JMS_ACTIVEMQ_OUTPUT_STREAM +
                                                      " or " +
                                                      ActiveMQJMSConstants.JMS_ACTIVEMQ_SAVE_STREAM +
                                                      "?");
         }
         else {
            throw ActiveMQJMSClientBundle.BUNDLE.messageNotWritable();
         }
      }

      if (name == null) {
         throw ActiveMQJMSClientBundle.BUNDLE.nullArgumentNotAllowed("property");
      }

      if (name.equals("")) {
         throw new IllegalArgumentException("The name of a property must not be an empty String.");
      }

      if (!isValidJavaIdentifier(name)) {
         throw ActiveMQJMSClientBundle.BUNDLE.invalidJavaIdentifier(name);
      }

      if (ActiveMQMessage.reservedIdentifiers.contains(name)) {
         throw new JMSRuntimeException("The property name '" + name + "' is reserved due to selector syntax.");
      }

      if (name.startsWith("JMS_ACTIVEMQ")) {
         throw new JMSRuntimeException("The property name '" + name + "' is illegal since it starts with JMS_ACTIVEMQ");
      }
   }

   private boolean isValidJavaIdentifier(final String s) {
      if (s == null || s.length() == 0) {
         return false;
      }

      char[] c = s.toCharArray();

      if (!Character.isJavaIdentifierStart(c[0])) {
         return false;
      }

      for (int i = 1; i < c.length; i++) {
         if (!Character.isJavaIdentifierPart(c[i])) {
            return false;
         }
      }

      return true;
   }

   private void checkPriority(final int priority) throws JMSException {
      if (priority < 0 || priority > 9) {
         throw new JMSException(priority + " is not valid: priority must be between 0 and 9");
      }
   }

   // Inner classes -------------------------------------------------
}
