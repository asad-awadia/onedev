package io.onedev.server.search.entitytext;

import com.google.common.collect.Lists;
import io.onedev.commons.loader.ManagedSerializedForm;
import io.onedev.server.cluster.ClusterManager;
import io.onedev.server.entitymanager.BuildManager;
import io.onedev.server.entitymanager.ProjectManager;
import io.onedev.server.entitymanager.PullRequestReviewManager;
import io.onedev.server.entitymanager.UserManager;
import io.onedev.server.event.Listen;
import io.onedev.server.event.project.pullrequest.*;
import io.onedev.server.model.Project;
import io.onedev.server.model.PullRequest;
import io.onedev.server.model.PullRequestComment;
import io.onedev.server.model.support.pullrequest.changedata.PullRequestChangeData;
import io.onedev.server.model.support.pullrequest.changedata.PullRequestDescriptionChangeData;
import io.onedev.server.model.support.pullrequest.changedata.PullRequestTitleChangeData;
import io.onedev.server.persistence.TransactionManager;
import io.onedev.server.persistence.annotation.Sessional;
import io.onedev.server.persistence.dao.Dao;
import io.onedev.server.security.SecurityUtils;
import io.onedev.server.security.permission.ReadCode;
import io.onedev.server.util.concurrent.BatchWorkManager;
import io.onedev.server.util.criteria.Criteria;
import io.onedev.server.util.lucene.BooleanQueryBuilder;
import io.onedev.server.util.lucene.LuceneUtils;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.ObjectStreamException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.apache.lucene.document.LongPoint.newExactQuery;

@Singleton
public class DefaultPullRequestTextManager extends ProjectTextManager<PullRequest> 
		implements PullRequestTextManager {

	private static final String FIELD_NUMBER = "number";
	
	private static final String FIELD_TITLE = "title";
	
	private static final String FIELD_DESCRIPTION = "description";

	private static final String FIELD_COMMENTS = "comments";
	
	private final PullRequestReviewManager reviewManager;
	
	private final BuildManager buildManager;
	
	private final UserManager userManager;
	
	@Inject
	public DefaultPullRequestTextManager(Dao dao, UserManager userManager, BatchWorkManager batchWorkManager, 
										 TransactionManager transactionManager, ProjectManager projectManager, 
										 PullRequestReviewManager reviewManager, BuildManager buildManager, 
										 ClusterManager clusterManager) {
		super(dao, batchWorkManager, transactionManager, projectManager, clusterManager);
		this.reviewManager = reviewManager;
		this.buildManager = buildManager;
		this.userManager = userManager;
	}

	public Object writeReplace() throws ObjectStreamException {
		return new ManagedSerializedForm(PullRequestTextManager.class);
	}
	
	@Override
	protected int getIndexVersion() {
		return 4;
	}

	@Override
	protected void addFields(Document document, PullRequest entity) {
		document.add(new LongPoint(FIELD_NUMBER, entity.getNumber()));
		document.add(new TextField(FIELD_TITLE, entity.getTitle(), Store.NO));
		if (entity.getDescription() != null)
			document.add(new TextField(FIELD_DESCRIPTION, entity.getDescription(), Store.NO));
		StringBuilder builder = new StringBuilder();
		for (PullRequestComment comment: entity.getComments()) {
			if (!comment.getUser().equals(userManager.getSystem()))
				builder.append(comment.getContent()).append("\n");
		}
		if (builder.length() != 0)
			document.add(new TextField(FIELD_COMMENTS, builder.toString(), Store.NO));
	}

	@Sessional
	@Listen
	public void on(PullRequestOpened event) {
		requestIndex(event.getRequest());
	}

	@Sessional
	@Listen
	public void on(PullRequestChanged event) {
		PullRequestChangeData data = event.getChange().getData();
		if (data instanceof PullRequestTitleChangeData || data instanceof PullRequestDescriptionChangeData)
			requestIndex(event.getRequest());
	}

	@Sessional
	@Listen
	public void on(PullRequestCommentCreated event) {
		requestIndex(event.getRequest());
	}

	@Sessional
	@Listen
	public void on(PullRequestCommentEdited event) {
		requestIndex(event.getRequest());
	}

	@Sessional
	@Listen
	public void on(PullRequestCommentDeleted event) {
		requestIndex(event.getRequest());
	}

	@Sessional
	@Listen
	public void on(PullRequestsDeleted event) {
		clusterManager.submitToAllServers(() -> {
			deleteEntities(event.getRequestIds());
			return null;
		});
	}

	@Sessional
	@Listen
	public void on(PullRequestDeleted event) {
		clusterManager.submitToAllServers(() -> {
			deleteEntities(Lists.newArrayList(event.getRequestId()));
			return null;			
		});
	}
	
	@Nullable
	private Query buildQuery(@Nullable Project project, String queryString) {
		BooleanQueryBuilder queryBuilder = new BooleanQueryBuilder();
		if (project != null) {
			queryBuilder.add(newExactQuery(FIELD_PROJECT_ID, project.getId()), Occur.MUST);
		} else if (!SecurityUtils.isAdministrator()) {
			Collection<Project> projects = projectManager.getPermittedProjects(new ReadCode());
			if (!projects.isEmpty()) {
				Query projectsQuery = Criteria.forManyValues(
						FIELD_PROJECT_ID, 
						projects.stream().map(it->it.getId()).collect(Collectors.toSet()), 
						projectManager.getIds());
				queryBuilder.add(projectsQuery, Occur.MUST);
			} else {
				return null;
			}
		}
		BooleanQueryBuilder contentQueryBuilder = new BooleanQueryBuilder();
		
		String numberString = queryString;
		if (numberString.startsWith("#")) 
			numberString = numberString.substring(1);
		try {
			Long number = Long.valueOf(numberString);
			contentQueryBuilder.add(new BoostQuery(newExactQuery(FIELD_NUMBER, number), 1f), Occur.SHOULD);
		} catch (NumberFormatException ignored) {
		}
		
		try (Analyzer analyzer = newAnalyzer()) {
			Map<String, Float> boosts = new HashMap<>();
			boosts.put(FIELD_TITLE, 0.75f);
			boosts.put(FIELD_DESCRIPTION, 0.5f);
			boosts.put(FIELD_COMMENTS, 0.25f);
			MultiFieldQueryParser parser = new MultiFieldQueryParser(
					new String[] {FIELD_TITLE, FIELD_DESCRIPTION, FIELD_COMMENTS}, analyzer, boosts) {
				
				protected Query newTermQuery(Term term, float boost) {
					return new BoostQuery(new PrefixQuery(term), boost);
				}
				
			};
			var escaped = LuceneUtils.escape(queryString);
			if (escaped != null)
				contentQueryBuilder.add(parser.parse(escaped), Occur.SHOULD);
			else 
				return null;
		} catch (ParseException e) {
			throw new RuntimeException(e);
		}
		queryBuilder.add(contentQueryBuilder.build(), Occur.MUST);
		
		return queryBuilder.build();		
	}

	@Override
	public List<PullRequest> query(@Nullable Project project, String queryString, 
		boolean loadReviewsAndBuilds, int firstResult, int maxResults) {
		List<PullRequest> requests = search(buildQuery(project, queryString), firstResult, maxResults);
		if (!requests.isEmpty() && loadReviewsAndBuilds) {
			reviewManager.populateReviews(requests);
			buildManager.populateBuilds(requests);
		}
		return requests;
	}

	@Override
	public long count(@Nullable Project project, String queryString) {
		return count(buildQuery(project, queryString));
	}
	
}
