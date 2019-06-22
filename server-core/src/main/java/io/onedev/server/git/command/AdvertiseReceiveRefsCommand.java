package io.onedev.server.git.command;

import java.io.File;
import java.io.OutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;

import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.LineConsumer;

public class AdvertiseReceiveRefsCommand extends GitCommand<Void> {

	private static final Logger logger = LoggerFactory.getLogger(AdvertiseReceiveRefsCommand.class);
	
	private OutputStream output;
	
	public AdvertiseReceiveRefsCommand(File gitDir) {
		super(gitDir);
	}

	public AdvertiseReceiveRefsCommand output(OutputStream output) {
		this.output = output;
		return this;
	}
	
	@Override
	public Void call(Logger logger) {
		Preconditions.checkNotNull(output);
		
		Logger effectiveLogger = logger!=null?logger:AdvertiseReceiveRefsCommand.logger;
		Commandline cmd = cmd();
		cmd.addArgs("receive-pack", "--stateless-rpc", "--advertise-refs", ".");
		cmd.execute(output, new LineConsumer() {

			@Override
			public void consume(String line) {
				effectiveLogger.error(line);
			}
			
		}, logger).checkReturnCode();
		return null;
	}

}