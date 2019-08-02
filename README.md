Remember to add blow element in standalone.xml

            <spi name="storage">
                <provider name="readonly-property-file" enabled="true">
                    <properties>
                        <property name="path" value="${jboss.server.config.dir}/example-users.properties"/>
                    </properties>
                </provider>
            </spi>
