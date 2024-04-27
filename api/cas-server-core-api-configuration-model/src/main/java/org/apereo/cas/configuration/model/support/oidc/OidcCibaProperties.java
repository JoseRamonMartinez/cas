package org.apereo.cas.configuration.model.support.oidc;

import org.apereo.cas.configuration.support.DurationCapable;
import org.apereo.cas.configuration.support.RequiresModule;
import com.fasterxml.jackson.annotation.JsonFilter;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import java.io.Serial;
import java.io.Serializable;

/**
 * This is {@link OidcCibaProperties}.
 *
 * @author Misagh Moayyed
 * @since 7.1.0
 */
@RequiresModule(name = "cas-server-support-oidc")
@Getter
@Setter
@Accessors(chain = true)
@JsonFilter("OidcCibaProperties")
public class OidcCibaProperties implements Serializable {

    @Serial
    private static final long serialVersionUID = 313328615694269276L;

    /**
     * Hard timeout to kill the id token and expire it.
     */
    @DurationCapable
    private String maxTimeToLiveInSeconds = "PT5M";
    
    /**
     * Control CIBA notification settings
     * to authenticate the user via email, etc.
     */
    @NestedConfigurationProperty
    private OidcCibaVerificationProperties verification = new OidcCibaVerificationProperties();
}
