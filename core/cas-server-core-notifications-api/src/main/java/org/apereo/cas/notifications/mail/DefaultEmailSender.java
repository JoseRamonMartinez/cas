package org.apereo.cas.notifications.mail;

import org.apereo.cas.multitenancy.TenantCommunicationPolicy;
import org.apereo.cas.multitenancy.TenantDefinition;
import org.apereo.cas.multitenancy.TenantEmailCommunicationPolicy;
import org.apereo.cas.multitenancy.TenantExtractor;
import org.apereo.cas.util.function.FunctionUtils;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.jooq.lambda.Unchecked;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.ApplicationContext;
import org.springframework.context.MessageSource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import jakarta.mail.internet.MimeMessage;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * This is {@link DefaultEmailSender}.
 *
 * @author Misagh Moayyed
 * @since 7.0.0
 */
@RequiredArgsConstructor
@Slf4j
@Getter
public class DefaultEmailSender implements EmailSender {
    private final MessageSource messageSource;

    private final ApplicationContext applicationContext;

    private final List<EmailSenderCustomizer> emailSenderCustomizers;

    private final MailProperties mailProperties;

    private final ObjectProvider<SslBundles> sslBundles;

    private final TenantExtractor tenantExtractor;

    @Override
    public EmailCommunicationResult send(final EmailMessageRequest emailRequest) throws Exception {
        val mailSender = createMailSender(emailRequest);
        val connectionAvailable = mailSender != null && FunctionUtils.doAndHandle(() -> {
            mailSender.testConnection();
            return true;
        }, throwable -> false).get();

        val recipients = emailRequest.getRecipients();
        if (connectionAvailable) {
            val message = createEmailMessage(emailRequest, mailSender);
            emailSenderCustomizers.forEach(customizer -> customizer.customize(mailSender, emailRequest));
            mailSender.send(message);
        }

        return EmailCommunicationResult.builder()
            .success(connectionAvailable)
            .to(recipients)
            .body(emailRequest.getBody())
            .build();
    }

    protected MimeMessage createEmailMessage(final EmailMessageRequest emailRequest,
                                             final JavaMailSender mailSender) throws Exception {
        val recipients = emailRequest.getRecipients();
        val message = mailSender.createMimeMessage();
        val messageHelper = new MimeMessageHelper(message);
        messageHelper.setTo(recipients.toArray(ArrayUtils.EMPTY_STRING_ARRAY));

        val emailProperties = emailRequest.getEmailProperties();
        messageHelper.setText(emailRequest.getBody(), emailProperties.isHtml());

        val subject = determineEmailSubject(emailRequest, messageSource);
        messageHelper.setSubject(subject);

        findTenantEmailCommunicationPolicy(emailRequest)
            .filter(policy -> StringUtils.isNotBlank(policy.getFrom()))
            .ifPresentOrElse(
                Unchecked.consumer(policy -> messageHelper.setFrom(policy.getFrom())),
                Unchecked.runnable(() -> messageHelper.setFrom(emailProperties.getFrom()))
            );

        FunctionUtils.doIfNotBlank(emailProperties.getReplyTo(), messageHelper::setReplyTo);
        messageHelper.setValidateAddresses(emailProperties.isValidateAddresses());
        messageHelper.setPriority(emailProperties.getPriority());
        messageHelper.setCc(emailProperties.getCc().toArray(ArrayUtils.EMPTY_STRING_ARRAY));
        messageHelper.setBcc(emailProperties.getBcc().toArray(ArrayUtils.EMPTY_STRING_ARRAY));

        return message;
    }

    protected JavaMailSenderImpl createMailSender(final EmailMessageRequest emailRequest) {
        val sender = applyProperties(new JavaMailSenderImpl(), emailRequest);
        return StringUtils.isNotBlank(sender.getHost()) ? sender : null;
    }

    protected JavaMailSenderImpl applyProperties(final JavaMailSenderImpl sender,
                                                 final EmailMessageRequest emailRequest) {
        applyEmailServerProperties(sender, emailRequest);

        val javaMailProperties = asProperties(mailProperties.getProperties());
        val protocol = StringUtils.defaultIfBlank(mailProperties.getProtocol(), "smtp");

        val ssl = mailProperties.getSsl();
        if (ssl.isEnabled()) {
            javaMailProperties.setProperty("mail." + protocol + ".ssl.enable", "true");
        }
        if (StringUtils.isNotBlank(ssl.getBundle())) {
            val sslBundle = sslBundles.getObject().getBundle(ssl.getBundle());
            val socketFactory = sslBundle.createSslContext().getSocketFactory();
            javaMailProperties.put("mail." + protocol + ".ssl.socketFactory", socketFactory);
        }
        if (!javaMailProperties.isEmpty()) {
            sender.setJavaMailProperties(javaMailProperties);
        }
        return sender;
    }

    protected void applyEmailServerProperties(final JavaMailSenderImpl sender,
                                              final EmailMessageRequest emailMessageRequest) {
        val tenantEmailCommunicationPolicy = findTenantEmailCommunicationPolicy(emailMessageRequest);
        tenantEmailCommunicationPolicy.ifPresentOrElse(policy -> {
            sender.setHost(policy.getHost());
            if (policy.getPort() > 0) {
                sender.setPort(policy.getPort());
            }
            sender.setUsername(policy.getUsername());
            sender.setPassword(policy.getPassword());
        }, () -> {
            sender.setHost(mailProperties.getHost());
            if (mailProperties.getPort() != null) {
                sender.setPort(mailProperties.getPort());
            }
            sender.setUsername(mailProperties.getUsername());
            sender.setPassword(mailProperties.getPassword());
        });
        sender.setProtocol(mailProperties.getProtocol());
        sender.setDefaultEncoding(mailProperties.getDefaultEncoding().name());
    }

    private Optional<TenantEmailCommunicationPolicy> findTenantEmailCommunicationPolicy(
        final EmailMessageRequest emailMessageRequest) {
        return tenantExtractor.getTenantsManager()
            .findTenant(emailMessageRequest.getTenant())
            .map(TenantDefinition::getCommunicationPolicy)
            .filter(Objects::nonNull)
            .map(TenantCommunicationPolicy::getEmailCommunicationPolicy)
            .filter(Objects::nonNull)
            .stream()
            .findFirst();
    }

    private static Properties asProperties(final Map<String, String> source) {
        val properties = new Properties();
        properties.putAll(source);
        return properties;
    }
}
